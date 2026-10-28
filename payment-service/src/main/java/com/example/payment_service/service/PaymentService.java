package com.example.payment_service.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PageResponse;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.ProviderRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.rules.PaymentRuleValidator;

@Service
public class PaymentService {

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final TransactionRepository transactionRepository;

	private final PaymentRuleValidator ruleValidator;

	private final PaymentProvider paymentProvider;

	private final ApplicationEventPublisher eventPublisher;

	public PaymentService(TransactionRepository transactionRepository, PaymentRuleValidator ruleValidator,
			PaymentProvider paymentProvider, ApplicationEventPublisher eventPublisher) {
		this.transactionRepository = transactionRepository;
		this.ruleValidator = ruleValidator;
		this.paymentProvider = paymentProvider;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public PaymentResponse process(Merchant merchant, CreatePaymentRequest request) {
		return process(merchant, request, null).payment();
	}

	@Transactional
	public PaymentOutcome process(Merchant merchant, CreatePaymentRequest request, String idempotencyKey) {
		String requestHash = null;
		if (idempotencyKey != null) {
			requestHash = PaymentRequestHasher.hash(request);
			Optional<Transaction> existing = transactionRepository
				.findByMerchantIdAndIdempotencyKey(merchant.getId(), idempotencyKey);
			if (existing.isPresent()) {
				if (!existing.get().getRequestHash().equals(requestHash)) {
					throw new IdempotencyKeyReusedException();
				}
				return new PaymentOutcome(PaymentResponse.from(existing.get()), true);
			}
		}

		ruleValidator.validate(request.paymentMethod(), request.currency(), request.amount());

		Transaction transaction = new Transaction();
		transaction.setMerchant(merchant);
		transaction.setIdempotencyKey(idempotencyKey);
		transaction.setRequestHash(requestHash);
		transaction.setAmount(request.amount().setScale(2));
		transaction.setCurrency(request.currency());
		transaction.setPaymentMethod(request.paymentMethod());
		transaction.setStatus(TransactionStatus.PENDING);
		transaction = transactionRepository.save(transaction);

		ProviderResult result = paymentProvider.process(new ProviderRequest(transaction.getId(),
				transaction.getPaymentMethod(), transaction.getCurrency(), transaction.getAmount()));
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
