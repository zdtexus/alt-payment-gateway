package com.example.payment_service.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PendingAction;
import com.example.payment_service.entity.RefundStatus;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.CaptureDeclinedException;
import com.example.payment_service.error.CaptureExceedsAuthorizedException;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.ManualCaptureNotSupportedException;
import com.example.payment_service.error.PaymentNotCapturableException;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.error.PaymentNotVoidableException;
import com.example.payment_service.error.VoidDeclinedException;
import com.example.payment_service.error.WebhookPaymentNotFoundException;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.AuthorizingProvider;
import com.example.payment_service.provider.CaptureRequest;
import com.example.payment_service.provider.PaymentProviderRegistry;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.ProviderWebhook;
import com.example.payment_service.provider.VoidRequest;
import com.example.payment_service.reconciliation.ReconciliationProperties;
import com.example.payment_service.repository.RefundRepository;
import com.example.payment_service.repository.TransactionRepository;

@Service
public class CaptureService {

	private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

	private final TransactionRepository transactionRepository;

	private final RefundRepository refundRepository;

	private final PaymentProviderRegistry providerRegistry;

	private final TransactionTemplate transactionTemplate;

	private final ApplicationEventPublisher eventPublisher;

	private final ReconciliationProperties reconciliation;

	public CaptureService(TransactionRepository transactionRepository, RefundRepository refundRepository,
			PaymentProviderRegistry providerRegistry, TransactionTemplate transactionTemplate,
			ApplicationEventPublisher eventPublisher, ReconciliationProperties reconciliation) {
		this.transactionRepository = transactionRepository;
		this.refundRepository = refundRepository;
		this.providerRegistry = providerRegistry;
		this.transactionTemplate = transactionTemplate;
		this.eventPublisher = eventPublisher;
		this.reconciliation = reconciliation;
	}

	public PaymentOutcome capture(Merchant merchant, UUID paymentId, BigDecimal amount, String idempotencyKey) {
		if (amount != null && (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)) {
			throw new IllegalArgumentException("Capture amount must be greater than 0 with at most 2 decimals");
		}
		return perform(merchant, paymentId, PendingAction.CAPTURE, amount, idempotencyKey, Mode.REQUEST);
	}

	public PaymentOutcome voidAuthorization(Merchant merchant, UUID paymentId, String idempotencyKey) {
		return perform(merchant, paymentId, PendingAction.VOID, null, idempotencyKey, Mode.REQUEST);
	}

	public PaymentOutcome voidExpired(UUID paymentId) {
		return perform(null, paymentId, PendingAction.VOID, null, null, Mode.EXPIRY);
	}

	public PaymentResponse settleFromStatusCheck(UUID paymentId, PendingAction action, ProviderResult result) {
		return transactionTemplate.execute((status) -> applyResult(paymentId, action, result, Mode.REQUEST)).payment();
	}

	public void settleFromWebhook(String provider, ProviderWebhook webhook) {
		PendingAction action = (webhook.type() == ProviderWebhook.Type.CAPTURE) ? PendingAction.CAPTURE
				: PendingAction.VOID;
		transactionTemplate.executeWithoutResult((status) -> {
			Transaction payment = transactionRepository.findLockedById(webhook.paymentId())
				.filter((candidate) -> provider.equals(candidate.getProvider()))
				.orElseThrow(() -> new WebhookPaymentNotFoundException(provider, webhook.result().reference()));
			if (payment.getPendingAction() == action) {
				apply(payment, action, webhook.result(), Mode.REQUEST);
			}
			else if (!alreadyApplied(payment, action, webhook.result())) {
				log.warn("{} webhook from {} for payment {} reports {} but the payment is {} without a pending {}; ignored",
						action, provider, payment.getId(), webhook.result().getClass().getSimpleName(),
						payment.getStatus(), action);
			}
		});
	}

	private static boolean alreadyApplied(Transaction payment, PendingAction action, ProviderResult result) {
		TransactionStatus done = (action == PendingAction.CAPTURE) ? TransactionStatus.SUCCEEDED
				: TransactionStatus.VOIDED;
		return result instanceof ProviderResult.Pending
				|| (result instanceof ProviderResult.Approved && payment.getStatus() == done);
	}

	private PaymentOutcome perform(Merchant merchant, UUID paymentId, PendingAction action, BigDecimal amount,
			String idempotencyKey, Mode mode) {
		String requestHash = (idempotencyKey != null) ? ActionRequestHasher.hash(action, amount) : null;
		Claim claim = transactionTemplate
			.execute((status) -> claim(merchant, paymentId, action, amount, idempotencyKey, requestHash, mode));
		if (claim.replayed()) {
			return new PaymentOutcome(claim.payment(), true);
		}
		ProviderResult result;
		try {
			result = (action == PendingAction.CAPTURE)
					? claim.provider()
						.capture(new CaptureRequest(paymentId, claim.reference(), claim.currency(), claim.amount()))
					: claim.provider().voidAuthorization(new VoidRequest(paymentId, claim.reference()));
		}
		catch (RuntimeException ex) {
			if (mode == Mode.EXPIRY) {
				transactionTemplate.executeWithoutResult((status) -> release(paymentId, action));
				throw ex;
			}
			log.error("{} of payment {} failed at its provider; the payment stays AUTHORIZED with the {} pending",
					action, paymentId, action, ex);
			return new PaymentOutcome(claim.payment(), false);
		}
		Settled settled = transactionTemplate.execute((status) -> applyResult(paymentId, action, result, mode));
		if (settled.declineReason() != null) {
			throw (action == PendingAction.CAPTURE) ? new CaptureDeclinedException(settled.declineReason())
					: new VoidDeclinedException(settled.declineReason());
		}
		return new PaymentOutcome(settled.payment(), false);
	}

	private Claim claim(Merchant merchant, UUID paymentId, PendingAction action, BigDecimal amount,
			String idempotencyKey, String requestHash, Mode mode) {
		Transaction payment = ((merchant != null)
				? transactionRepository.findLockedByIdAndMerchantId(paymentId, merchant.getId())
				: transactionRepository.findLockedById(paymentId))
			.orElseThrow(() -> new PaymentNotFoundException(paymentId));
		if (idempotencyKey != null && idempotencyKey.equals(payment.getActionIdempotencyKey())) {
			if (!requestHash.equals(payment.getActionRequestHash())) {
				throw new IdempotencyKeyReusedException();
			}
			return Claim.replay(response(payment));
		}
		if (payment.getStatus() != TransactionStatus.AUTHORIZED) {
			throw refused(action, "Payment " + paymentId + " is " + payment.getStatus() + "; only AUTHORIZED payments can be "
					+ ((action == PendingAction.CAPTURE) ? "captured" : "voided"));
		}
		if (payment.getPendingAction() != null) {
			throw refused(action, "A " + payment.getPendingAction().name().toLowerCase() + " of payment " + paymentId
					+ " is already in progress");
		}
		if (action == PendingAction.CAPTURE && !Instant.now().isBefore(payment.getAuthorizationExpiresAt())) {
			throw refused(action, "The authorization of payment " + paymentId + " expired at "
					+ payment.getAuthorizationExpiresAt() + "; it can only be voided");
		}
		AuthorizingProvider provider = providerRegistry.findAuthorizingProvider(payment.getProvider())
			.orElseThrow(() -> new ManualCaptureNotSupportedException(payment.getPaymentMethod()));
		BigDecimal requested = null;
		if (action == PendingAction.CAPTURE) {
			requested = (amount != null) ? amount.setScale(2) : payment.getAuthorizedAmount();
			if (requested.compareTo(payment.getAuthorizedAmount()) > 0) {
				throw new CaptureExceedsAuthorizedException(requested, payment.getAuthorizedAmount());
			}
		}
		payment.setPendingAction(action);
		payment.setPendingCaptureAmount(requested);
		payment.setActionIdempotencyKey(idempotencyKey);
		payment.setActionRequestHash(requestHash);
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		payment.setActionRequestedAt(now);
		if (mode == Mode.REQUEST) {
			payment.setNextCheckAt(now.plus(reconciliation.minAge()));
			payment.setCheckAttempts(0);
		}
		return new Claim(response(payment), false, provider, payment.getProviderReference(), payment.getCurrency(),
				requested);
	}

	private void release(UUID paymentId, PendingAction action) {
		Transaction payment = transactionRepository.findLockedById(paymentId).orElseThrow();
		if (payment.getPendingAction() == action) {
			clearPendingAction(payment);
		}
	}

	private Settled applyResult(UUID paymentId, PendingAction action, ProviderResult result, Mode mode) {
		Transaction payment = transactionRepository.findLockedById(paymentId).orElseThrow();
		if (payment.getPendingAction() != action) {
			log.warn("Payment {} no longer had a pending {} when its provider answered; the answer is ignored",
					paymentId, action);
			return new Settled(response(payment), null);
		}
		return apply(payment, action, result, mode);
	}

	private Settled apply(Transaction payment, PendingAction action, ProviderResult result, Mode mode) {
		UUID paymentId = payment.getId();
		switch (result) {
			case ProviderResult.Approved approved -> {
				if (action == PendingAction.CAPTURE) {
					payment.setStatus(TransactionStatus.SUCCEEDED);
					payment.setCapturedAmount(payment.getPendingCaptureAmount());
				}
				else {
					payment.setStatus(TransactionStatus.VOIDED);
				}
				clearPendingAction(payment);
				payment.setNextCheckAt(null);
				payment.setProcessedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
				eventPublisher.publishEvent(PaymentProcessedEvent.from(payment));
				return new Settled(response(payment), null);
			}
			case ProviderResult.Declined(String reason, String reference) -> {
				log.warn("The provider declined the {} of payment {}: {}; the payment stays AUTHORIZED", action,
						paymentId, reason);
				clearPendingAction(payment);
				payment.setActionIdempotencyKey(null);
				payment.setActionRequestHash(null);
				if (mode == Mode.REQUEST) {
					payment.setNextCheckAt(null);
				}
				return new Settled(response(payment), reason);
			}
			case ProviderResult.Pending pending -> {
				return new Settled(response(payment), null);
			}
		}
	}

	private static void clearPendingAction(Transaction payment) {
		payment.setPendingAction(null);
		payment.setPendingCaptureAmount(null);
		payment.setActionRequestedAt(null);
	}

	private static RuntimeException refused(PendingAction action, String message) {
		return (action == PendingAction.CAPTURE) ? new PaymentNotCapturableException(message)
				: new PaymentNotVoidableException(message);
	}

	private PaymentResponse response(Transaction payment) {
		if (payment.getStatus() != TransactionStatus.SUCCEEDED) {
			return PaymentResponse.from(payment, PaymentResponse.NOTHING_REFUNDED);
		}
		BigDecimal refunded = refundRepository.sumAmount(payment.getId(), EnumSet.of(RefundStatus.SUCCEEDED));
		return PaymentResponse.from(payment, refunded.setScale(2));
	}

	private record Claim(PaymentResponse payment, boolean replayed, AuthorizingProvider provider, String reference,
			String currency, BigDecimal amount) {

		static Claim replay(PaymentResponse payment) {
			return new Claim(payment, true, null, null, null, null);
		}

	}

	private record Settled(PaymentResponse payment, String declineReason) {
	}

	private enum Mode {

		REQUEST, EXPIRY

	}

}
