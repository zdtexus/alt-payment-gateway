package com.example.payment_service.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PageResponse;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.CaptureMethod;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.error.WebhookPaymentNotFoundException;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.AuthorizationResult;
import com.example.payment_service.provider.AuthorizingProvider;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.PaymentProviderRouter;
import com.example.payment_service.provider.ProviderRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.ProviderWebhook;
import com.example.payment_service.reconciliation.ReconciliationProperties;
import com.example.payment_service.repository.RefundRepository;
import com.example.payment_service.repository.RefundTotal;
import com.example.payment_service.entity.RefundStatus;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.rules.PaymentRuleValidator;

@Service
public class PaymentService {

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final TransactionRepository transactionRepository;

	private final PaymentRuleValidator ruleValidator;

	private final PaymentProviderRouter providerRouter;

	private final ApplicationEventPublisher eventPublisher;

	private final TransactionTemplate transactionTemplate;

	private final ReconciliationProperties reconciliation;

	private final RefundRepository refundRepository;

	public PaymentService(TransactionRepository transactionRepository, PaymentRuleValidator ruleValidator,
			PaymentProviderRouter providerRouter, ApplicationEventPublisher eventPublisher,
			TransactionTemplate transactionTemplate, ReconciliationProperties reconciliation,
			RefundRepository refundRepository) {
		this.transactionRepository = transactionRepository;
		this.ruleValidator = ruleValidator;
		this.providerRouter = providerRouter;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = transactionTemplate;
		this.reconciliation = reconciliation;
		this.refundRepository = refundRepository;
	}

	public PaymentResponse process(Merchant merchant, CreatePaymentRequest request) {
		return process(merchant, request, null).payment();
	}

	public PaymentOutcome process(Merchant merchant, CreatePaymentRequest request, String idempotencyKey) {
		if (idempotencyKey == null) {
			return callProvider(
					transactionTemplate.execute((status) -> createPending(merchant, request, null, null)));
		}
		String requestHash = PaymentRequestHasher.hash(request);
		Optional<PaymentOutcome> replayed = transactionTemplate
			.execute((status) -> replay(merchant, idempotencyKey, requestHash));
		if (replayed.isPresent()) {
			return replayed.get();
		}
		PendingPayment pending;
		try {
			pending = transactionTemplate
				.execute((status) -> createPending(merchant, request, idempotencyKey, requestHash));
		}
		catch (DataIntegrityViolationException ex) {
			return transactionTemplate.execute((status) -> replay(merchant, idempotencyKey, requestHash))
				.orElseThrow(() -> ex);
		}
		return callProvider(pending);
	}

	private Optional<PaymentOutcome> replay(Merchant merchant, String idempotencyKey, String requestHash) {
		return transactionRepository.findByMerchantIdAndIdempotencyKey(merchant.getId(), idempotencyKey)
			.map((existing) -> {
				if (!existing.getRequestHash().equals(requestHash)) {
					throw new IdempotencyKeyReusedException();
				}
				return new PaymentOutcome(withRefunds(existing), true);
			});
	}

	private PendingPayment createPending(Merchant merchant, CreatePaymentRequest request, String idempotencyKey,
			String requestHash) {
		ruleValidator.validate(request.paymentMethod(), request.currency(), request.amount());

		Transaction transaction = new Transaction();
		transaction.setMerchant(merchant);
		transaction.setIdempotencyKey(idempotencyKey);
		transaction.setRequestHash(requestHash);
		transaction.setAmount(request.amount().setScale(2));
		transaction.setCurrency(request.currency());
		transaction.setPaymentMethod(request.paymentMethod());
		transaction.setCaptureMethod(request.captureMethod());
		transaction.setStatus(TransactionStatus.PENDING);
		Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		transaction.setCreatedAt(createdAt);
		transaction.setNextCheckAt(createdAt.plus(reconciliation.minAge()));
		PaymentProvider provider = providerRouter.providerFor(request.paymentMethod());
		AuthorizingProvider authorizing = (request.captureMethod() == CaptureMethod.MANUAL)
				? providerRouter.authorizingProviderFor(request.paymentMethod()) : null;
		transaction.setProvider(provider.name());
		return new PendingPayment(transactionRepository.saveAndFlush(transaction), provider, authorizing);
	}

	private PaymentOutcome callProvider(PendingPayment pending) {
		if (pending.authorizing() != null) {
			return callAuthorizingProvider(pending);
		}
		Transaction transaction = pending.transaction();
		ProviderResult result;
		try {
			result = pending.provider()
				.process(new ProviderRequest(transaction.getId(), transaction.getPaymentMethod(),
						transaction.getCurrency(), transaction.getAmount()));
		}
		catch (RuntimeException ex) {
			log.error("Payment provider {} failed for payment {}; the payment stays PENDING",
					pending.provider().name(), transaction.getId(), ex);
			return new PaymentOutcome(PaymentResponse.from(transaction, PaymentResponse.NOTHING_REFUNDED), false);
		}
		return transactionTemplate.execute((status) -> applyResult(transaction.getId(), result));
	}

	private PaymentOutcome callAuthorizingProvider(PendingPayment pending) {
		Transaction transaction = pending.transaction();
		AuthorizationResult authorization;
		try {
			authorization = pending.authorizing()
				.authorize(new ProviderRequest(transaction.getId(), transaction.getPaymentMethod(),
						transaction.getCurrency(), transaction.getAmount()));
		}
		catch (RuntimeException ex) {
			log.error("Payment provider {} failed to authorize payment {}; the payment stays PENDING",
					pending.provider().name(), transaction.getId(), ex);
			return new PaymentOutcome(PaymentResponse.from(transaction, PaymentResponse.NOTHING_REFUNDED), false);
		}
		return transactionTemplate.execute((status) -> applyAuthorization(transaction.getId(), authorization));
	}

	private PaymentOutcome applyAuthorization(UUID paymentId, AuthorizationResult authorization) {
		Transaction transaction = transactionRepository.findLockedById(paymentId).orElseThrow();
		if (transaction.getStatus() != TransactionStatus.PENDING) {
			log.warn("Payment {} was already {} when its provider answered the authorization; the answer is ignored",
					paymentId, transaction.getStatus());
		}
		else {
			authorize(transaction, authorization);
		}
		return new PaymentOutcome(PaymentResponse.from(transaction, PaymentResponse.NOTHING_REFUNDED), false);
	}

	private void authorize(Transaction transaction, AuthorizationResult authorization) {
		if (authorization.result() instanceof ProviderResult.Approved(String reference)) {
			transaction.setStatus(TransactionStatus.AUTHORIZED);
			transaction.setProviderReference(reference);
			transaction.setAuthorizedAmount(transaction.getAmount());
			transaction.setAuthorizationExpiresAt(authorization.expiresAt().truncatedTo(ChronoUnit.MICROS));
			transaction.setNextCheckAt(null);
			transaction.setCheckAttempts(0);
		}
		else {
			recordResult(transaction, authorization.result());
		}
	}

	public TransactionStatus settleAuthorizationFromStatusCheck(UUID paymentId, AuthorizationResult authorization) {
		return transactionTemplate.execute((status) -> {
			Transaction transaction = transactionRepository.findLockedById(paymentId).orElseThrow();
			if (transaction.getStatus() == TransactionStatus.PENDING
					&& transaction.getCaptureMethod() == CaptureMethod.MANUAL) {
				authorize(transaction, authorization);
			}
			else {
				log.warn("Payment {} was already {} when its authorization status was checked; the answer is ignored",
						paymentId, transaction.getStatus());
			}
			return transaction.getStatus();
		});
	}

	public void settleAuthorizationFromWebhook(String provider, ProviderWebhook webhook) {
		transactionTemplate.executeWithoutResult((status) -> {
			String reference = webhook.result().reference();
			Transaction transaction = transactionRepository
				.findLockedByProviderAndProviderReference(provider, reference)
				.or(() -> findUnreferencedPayment(provider, webhook.paymentId()))
				.orElseThrow(() -> new WebhookPaymentNotFoundException(provider, reference));
			if (transaction.getCaptureMethod() != CaptureMethod.MANUAL) {
				log.warn("Authorization webhook from {} for payment {} ignored: the payment doesn't use manual capture",
						provider, transaction.getId());
			}
			else if (transaction.getStatus() == TransactionStatus.PENDING) {
				authorize(transaction, webhook.authorization());
			}
			else if (!matchesAuthorization(transaction.getStatus(), webhook.result())) {
				log.warn("Authorization webhook from {} for payment {} reports {} but the payment is already {}; ignored",
						provider, transaction.getId(), webhook.result().getClass().getSimpleName(),
						transaction.getStatus());
			}
		});
	}

	private static boolean matchesAuthorization(TransactionStatus status, ProviderResult result) {
		return switch (result) {
			case ProviderResult.Approved approved -> status == TransactionStatus.AUTHORIZED
					|| status == TransactionStatus.SUCCEEDED || status == TransactionStatus.VOIDED;
			case ProviderResult.Declined declined -> status == TransactionStatus.DECLINED;
			case ProviderResult.Pending pending -> true;
		};
	}

	private PaymentOutcome applyResult(UUID paymentId, ProviderResult result) {
		Transaction transaction = transactionRepository.findLockedById(paymentId).orElseThrow();
		if (transaction.getStatus() == TransactionStatus.PENDING) {
			settle(transaction, result);
		}
		else {
			log.warn("Payment {} was already {} when its provider answered; the answer is ignored", paymentId,
					transaction.getStatus());
		}
		return new PaymentOutcome(PaymentResponse.from(transaction, PaymentResponse.NOTHING_REFUNDED), false);
	}

	public TransactionStatus settleFromStatusCheck(UUID paymentId, ProviderResult result) {
		return transactionTemplate.execute((status) -> applyResult(paymentId, result)).payment().status();
	}

	public void settleFromWebhook(String provider, ProviderWebhook webhook) {
		transactionTemplate.executeWithoutResult((status) -> {
			String reference = webhook.result().reference();
			Transaction transaction = transactionRepository
				.findLockedByProviderAndProviderReference(provider, reference)
				.or(() -> findUnreferencedPayment(provider, webhook.paymentId()))
				.orElseThrow(() -> new WebhookPaymentNotFoundException(provider, reference));
			if (transaction.getStatus() == TransactionStatus.PENDING) {
				settle(transaction, webhook.result());
			}
			else if (!matches(transaction.getStatus(), webhook.result())) {
				log.warn("Webhook from {} for payment {} reports {} but the payment is already {}; ignored",
						provider, transaction.getId(), webhook.result().getClass().getSimpleName(),
						transaction.getStatus());
			}
		});
	}

	private Optional<Transaction> findUnreferencedPayment(String provider, UUID paymentId) {
		if (paymentId == null) {
			return Optional.empty();
		}
		return transactionRepository.findLockedById(paymentId)
			.filter((transaction) -> provider.equals(transaction.getProvider())
					&& transaction.getProviderReference() == null);
	}

	private static boolean matches(TransactionStatus status, ProviderResult result) {
		return switch (result) {
			case ProviderResult.Approved approved -> status == TransactionStatus.SUCCEEDED;
			case ProviderResult.Declined declined -> status == TransactionStatus.DECLINED;
			case ProviderResult.Pending pending -> true;
		};
	}

	private void settle(Transaction transaction, ProviderResult result) {
		if (transaction.getCaptureMethod() == CaptureMethod.MANUAL) {
			log.warn("Payment {} uses manual capture; a payment result can't settle its authorization, so it stays {}",
					transaction.getId(), transaction.getStatus());
			return;
		}
		recordResult(transaction, result);
	}

	private void recordResult(Transaction transaction, ProviderResult result) {
		switch (result) {
			case ProviderResult.Approved(String reference) -> {
				transaction.setStatus(TransactionStatus.SUCCEEDED);
				transaction.setProviderReference(reference);
			}
			case ProviderResult.Declined(String reason, String reference) -> {
				transaction.setStatus(TransactionStatus.DECLINED);
				transaction.setDeclineReason(reason);
				transaction.setProviderReference(reference);
			}
			case ProviderResult.Pending(String reference) -> {
				transaction.setProviderReference(reference);
				return;
			}
		}
		transaction.setProcessedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
		transaction.setNextCheckAt(null);
		eventPublisher.publishEvent(PaymentProcessedEvent.from(transaction));
	}

	@Transactional(readOnly = true)
	public PaymentResponse get(Merchant merchant, UUID id) {
		return transactionRepository.findByIdAndMerchantId(id, merchant.getId())
			.map(this::withRefunds)
			.orElseThrow(() -> new PaymentNotFoundException(id));
	}

	@Transactional(readOnly = true)
	public PageResponse<PaymentResponse> list(Merchant merchant, TransactionStatus status, int page, int size) {
		Pageable pageable = PageRequest.of(page, size, NEWEST_FIRST);
		Page<Transaction> transactions = (status != null)
				? transactionRepository.findByMerchantIdAndStatus(merchant.getId(), status, pageable)
				: transactionRepository.findByMerchantId(merchant.getId(), pageable);
		Map<UUID, BigDecimal> refunded = refundedAmounts(transactions.getContent());
		return PageResponse.from(transactions, (transaction) -> PaymentResponse.from(transaction,
				refunded.getOrDefault(transaction.getId(), PaymentResponse.NOTHING_REFUNDED)));
	}

	private PaymentResponse withRefunds(Transaction transaction) {
		BigDecimal refunded = refundRepository.sumAmount(transaction.getId(), EnumSet.of(RefundStatus.SUCCEEDED));
		return PaymentResponse.from(transaction, refunded.setScale(2));
	}

	private Map<UUID, BigDecimal> refundedAmounts(List<Transaction> transactions) {
		if (transactions.isEmpty()) {
			return Map.of();
		}
		return refundRepository
			.sumAmountByPayment(transactions.stream().map(Transaction::getId).toList(), RefundStatus.SUCCEEDED)
			.stream()
			.collect(Collectors.toMap(RefundTotal::getPaymentId, (total) -> total.getAmount().setScale(2)));
	}

	private record PendingPayment(Transaction transaction, PaymentProvider provider, AuthorizingProvider authorizing) {
	}

}
