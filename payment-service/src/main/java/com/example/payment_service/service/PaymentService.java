package com.example.payment_service.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

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
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.error.WebhookPaymentNotFoundException;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.PaymentProviderRouter;
import com.example.payment_service.provider.ProviderRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.ProviderWebhook;
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

	public PaymentService(TransactionRepository transactionRepository, PaymentRuleValidator ruleValidator,
			PaymentProviderRouter providerRouter, ApplicationEventPublisher eventPublisher,
			TransactionTemplate transactionTemplate) {
		this.transactionRepository = transactionRepository;
		this.ruleValidator = ruleValidator;
		this.providerRouter = providerRouter;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = transactionTemplate;
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
				return new PaymentOutcome(PaymentResponse.from(existing), true);
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
		transaction.setStatus(TransactionStatus.PENDING);
		PaymentProvider provider = providerRouter.providerFor(request.paymentMethod());
		transaction.setProvider(provider.name());
		return new PendingPayment(transactionRepository.saveAndFlush(transaction), provider);
	}

	private PaymentOutcome callProvider(PendingPayment pending) {
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
			return new PaymentOutcome(PaymentResponse.from(transaction), false);
		}
		return transactionTemplate.execute((status) -> applyResult(transaction.getId(), result));
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
		return new PaymentOutcome(PaymentResponse.from(transaction), false);
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
		eventPublisher.publishEvent(PaymentProcessedEvent.from(transaction));
	}

	@Transactional(readOnly = true)
	public PaymentResponse get(Merchant merchant, UUID id) {
		return transactionRepository.findByIdAndMerchantId(id, merchant.getId())
			.map(PaymentResponse::from)
			.orElseThrow(() -> new PaymentNotFoundException(id));
	}

	@Transactional(readOnly = true)
	public PageResponse<PaymentResponse> list(Merchant merchant, TransactionStatus status, int page, int size) {
		Pageable pageable = PageRequest.of(page, size, NEWEST_FIRST);
		Page<Transaction> transactions = (status != null)
				? transactionRepository.findByMerchantIdAndStatus(merchant.getId(), status, pageable)
				: transactionRepository.findByMerchantId(merchant.getId(), pageable);
		return PageResponse.from(transactions, PaymentResponse::from);
	}

	private record PendingPayment(Transaction transaction, PaymentProvider provider) {
	}

}
