package com.example.payment_service.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.example.payment_service.dto.RefundResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.Refund;
import com.example.payment_service.entity.RefundStatus;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.error.PaymentNotRefundableException;
import com.example.payment_service.error.RefundExceedsRemainingException;
import com.example.payment_service.error.RefundNotSupportedException;
import com.example.payment_service.provider.PaymentProviderRegistry;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.RefundRequest;
import com.example.payment_service.provider.RefundingProvider;
import com.example.payment_service.reconciliation.ReconciliationProperties;
import com.example.payment_service.repository.RefundRepository;
import com.example.payment_service.repository.TransactionRepository;

@Service
public class RefundService {

	private static final Logger log = LoggerFactory.getLogger(RefundService.class);

	private static final EnumSet<RefundStatus> RESERVING = EnumSet.of(RefundStatus.PENDING, RefundStatus.SUCCEEDED);

	private final TransactionRepository transactionRepository;

	private final RefundRepository refundRepository;

	private final PaymentProviderRegistry providerRegistry;

	private final TransactionTemplate transactionTemplate;

	private final ReconciliationProperties reconciliation;

	public RefundService(TransactionRepository transactionRepository, RefundRepository refundRepository,
			PaymentProviderRegistry providerRegistry, TransactionTemplate transactionTemplate,
			ReconciliationProperties reconciliation) {
		this.transactionRepository = transactionRepository;
		this.refundRepository = refundRepository;
		this.providerRegistry = providerRegistry;
		this.transactionTemplate = transactionTemplate;
		this.reconciliation = reconciliation;
	}

	public RefundOutcome create(Merchant merchant, UUID paymentId, BigDecimal amount, String reason,
			String idempotencyKey) {
		if (amount != null && (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)) {
			throw new IllegalArgumentException("Refund amount must be greater than 0 with at most 2 decimals");
		}
		String cleanReason = StringUtils.hasText(reason) ? reason.strip() : null;
		if (idempotencyKey == null) {
			return callProvider(transactionTemplate
				.execute((status) -> createPending(merchant, paymentId, amount, cleanReason, null, null)));
		}
		String requestHash = RefundRequestHasher.hash(paymentId, amount, cleanReason);
		Optional<RefundOutcome> replayed = transactionTemplate
			.execute((status) -> replay(merchant, idempotencyKey, requestHash));
		if (replayed.isPresent()) {
			return replayed.get();
		}
		PendingRefund pending;
		try {
			pending = transactionTemplate.execute(
					(status) -> createPending(merchant, paymentId, amount, cleanReason, idempotencyKey, requestHash));
		}
		catch (DataIntegrityViolationException ex) {
			return transactionTemplate.execute((status) -> replay(merchant, idempotencyKey, requestHash))
				.orElseThrow(() -> ex);
		}
		return callProvider(pending);
	}

	private Optional<RefundOutcome> replay(Merchant merchant, String idempotencyKey, String requestHash) {
		return refundRepository.findByMerchantIdAndIdempotencyKey(merchant.getId(), idempotencyKey).map((existing) -> {
			if (!existing.getRequestHash().equals(requestHash)) {
				throw new IdempotencyKeyReusedException();
			}
			return new RefundOutcome(response(existing), true);
		});
	}

	private PendingRefund createPending(Merchant merchant, UUID paymentId, BigDecimal amount, String reason,
			String idempotencyKey, String requestHash) {
		Transaction payment = transactionRepository.findLockedByIdAndMerchantId(paymentId, merchant.getId())
			.orElseThrow(() -> new PaymentNotFoundException(paymentId));
		if (payment.getStatus() != TransactionStatus.SUCCEEDED) {
			throw new PaymentNotRefundableException(paymentId, payment.getStatus());
		}
		RefundingProvider provider = providerRegistry.findRefundingProvider(payment.getProvider())
			.orElseThrow(RefundNotSupportedException::new);
		BigDecimal remaining = payment.getAmount().subtract(refundRepository.sumAmount(paymentId, RESERVING));
		BigDecimal requested = (amount != null) ? amount.setScale(2) : remaining;
		if (remaining.signum() <= 0 || requested.compareTo(remaining) > 0) {
			throw new RefundExceedsRemainingException(requested, remaining.max(BigDecimal.ZERO.setScale(2)));
		}

		Refund refund = new Refund();
		refund.setTransaction(payment);
		refund.setMerchant(payment.getMerchant());
		refund.setAmount(requested);
		refund.setStatus(RefundStatus.PENDING);
		refund.setReason(reason);
		refund.setIdempotencyKey(idempotencyKey);
		refund.setRequestHash(requestHash);
		Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		refund.setCreatedAt(createdAt);
		refund.setNextCheckAt(createdAt.plus(reconciliation.minAge()));
		Refund saved = refundRepository.saveAndFlush(refund);
		return new PendingRefund(saved, payment.getId(), payment.getProviderReference(), payment.getCurrency(),
				provider);
	}

	private RefundOutcome callProvider(PendingRefund pending) {
		Refund refund = pending.refund();
		ProviderResult result;
		try {
			result = pending.provider()
				.refund(new RefundRequest(refund.getId(), pending.paymentId(), pending.paymentReference(),
						pending.currency(), refund.getAmount()));
		}
		catch (RuntimeException ex) {
			log.error("Refund {} of payment {} failed at its provider; the refund stays PENDING", refund.getId(),
					pending.paymentId(), ex);
			return new RefundOutcome(RefundResponse.from(refund, pending.paymentId(), pending.currency()), false);
		}
		return transactionTemplate.execute((status) -> applyResult(refund.getId(), result));
	}

	private RefundOutcome applyResult(UUID refundId, ProviderResult result) {
		Refund refund = refundRepository.findLockedById(refundId).orElseThrow();
		if (refund.getStatus() == RefundStatus.PENDING) {
			settle(refund, result);
		}
		else {
			log.warn("Refund {} was already {} when its provider answered; the answer is ignored", refundId,
					refund.getStatus());
		}
		return new RefundOutcome(response(refund), false);
	}

	private void settle(Refund refund, ProviderResult result) {
		switch (result) {
			case ProviderResult.Approved(String reference) -> {
				refund.setStatus(RefundStatus.SUCCEEDED);
				refund.setProviderReference(reference);
			}
			case ProviderResult.Declined(String reason, String reference) -> {
				refund.setStatus(RefundStatus.DECLINED);
				refund.setDeclineReason(reason);
				refund.setProviderReference(reference);
			}
			case ProviderResult.Pending(String reference) -> {
				refund.setProviderReference(reference);
				return;
			}
		}
		refund.setProcessedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
		refund.setNextCheckAt(null);
	}

	private static RefundResponse response(Refund refund) {
		Transaction payment = refund.getTransaction();
		return RefundResponse.from(refund, payment.getId(), payment.getCurrency());
	}

	private record PendingRefund(Refund refund, UUID paymentId, String paymentReference, String currency,
			RefundingProvider provider) {
	}

}
