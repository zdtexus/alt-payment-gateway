package com.example.payment_service.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

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
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.PaymentProviderRouter;
import com.example.payment_service.provider.ProviderRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.rules.PaymentRuleValidator;

@Service
public class PaymentService {

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
			return transactionTemplate.execute((status) -> create(merchant, request, null, null));
		}
		String requestHash = PaymentRequestHasher.hash(request);
		try {
			return transactionTemplate.execute((status) -> replay(merchant, idempotencyKey, requestHash)
				.orElseGet(() -> create(merchant, request, idempotencyKey, requestHash)));
		}
		catch (DataIntegrityViolationException ex) {
			return transactionTemplate.execute((status) -> replay(merchant, idempotencyKey, requestHash))
				.orElseThrow(() -> ex);
		}
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

	private PaymentOutcome create(Merchant merchant, CreatePaymentRequest request, String idempotencyKey,
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
		transaction = transactionRepository.saveAndFlush(transaction);

		ProviderResult result = providerRouter.providerFor(transaction.getPaymentMethod())
			.process(new ProviderRequest(transaction.getId(), transaction.getPaymentMethod(), transaction.getCurrency(),
					transaction.getAmount()));
		switch (result) {
			case ProviderResult.Approved approved -> transaction.setStatus(TransactionStatus.SUCCEEDED);
			case ProviderResult.Declined(String reason) -> {
				transaction.setStatus(TransactionStatus.DECLINED);
				transaction.setDeclineReason(reason);
			}
		}
		transaction.setProcessedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
		eventPublisher.publishEvent(PaymentProcessedEvent.from(transaction));
		return new PaymentOutcome(PaymentResponse.from(transaction), false);
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

}
