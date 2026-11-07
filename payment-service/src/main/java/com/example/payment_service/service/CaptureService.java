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
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.AuthorizingProvider;
import com.example.payment_service.provider.CaptureRequest;
import com.example.payment_service.provider.PaymentProviderRegistry;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.VoidRequest;
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

	public CaptureService(TransactionRepository transactionRepository, RefundRepository refundRepository,
			PaymentProviderRegistry providerRegistry, TransactionTemplate transactionTemplate,
			ApplicationEventPublisher eventPublisher) {
		this.transactionRepository = transactionRepository;
		this.refundRepository = refundRepository;
		this.providerRegistry = providerRegistry;
		this.transactionTemplate = transactionTemplate;
		this.eventPublisher = eventPublisher;
	}

	public PaymentOutcome capture(Merchant merchant, UUID paymentId, BigDecimal amount, String idempotencyKey) {
		if (amount != null && (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)) {
			throw new IllegalArgumentException("Capture amount must be greater than 0 with at most 2 decimals");
		}
		return perform(merchant, paymentId, PendingAction.CAPTURE, amount, idempotencyKey);
	}

	public PaymentOutcome voidAuthorization(Merchant merchant, UUID paymentId, String idempotencyKey) {
		return perform(merchant, paymentId, PendingAction.VOID, null, idempotencyKey);
	}

	private PaymentOutcome perform(Merchant merchant, UUID paymentId, PendingAction action, BigDecimal amount,
			String idempotencyKey) {
		String requestHash = (idempotencyKey != null) ? ActionRequestHasher.hash(action, amount) : null;
		Claim claim = transactionTemplate
			.execute((status) -> claim(merchant, paymentId, action, amount, idempotencyKey, requestHash));
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
			log.error("{} of payment {} failed at its provider; the payment stays AUTHORIZED with the {} pending",
					action, paymentId, action, ex);
			return new PaymentOutcome(claim.payment(), false);
		}
		Settled settled = transactionTemplate.execute((status) -> applyResult(paymentId, action, result));
		if (settled.declineReason() != null) {
			throw (action == PendingAction.CAPTURE) ? new CaptureDeclinedException(settled.declineReason())
					: new VoidDeclinedException(settled.declineReason());
		}
		return new PaymentOutcome(settled.payment(), false);
	}

	private Claim claim(Merchant merchant, UUID paymentId, PendingAction action, BigDecimal amount,
			String idempotencyKey, String requestHash) {
		Transaction payment = transactionRepository.findLockedByIdAndMerchantId(paymentId, merchant.getId())
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
		return new Claim(response(payment), false, provider, payment.getProviderReference(), payment.getCurrency(),
				requested);
	}

	private Settled applyResult(UUID paymentId, PendingAction action, ProviderResult result) {
		Transaction payment = transactionRepository.findLockedById(paymentId).orElseThrow();
		if (payment.getPendingAction() != action) {
			log.warn("Payment {} no longer had a pending {} when its provider answered; the answer is ignored",
					paymentId, action);
			return new Settled(response(payment), null);
		}
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

}
