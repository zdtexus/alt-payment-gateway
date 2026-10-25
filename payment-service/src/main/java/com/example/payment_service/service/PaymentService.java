package com.example.payment_service.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.ProviderRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.rules.PaymentRuleValidator;

@Service
public class PaymentService {

	private final TransactionRepository transactionRepository;

	private final PaymentRuleValidator ruleValidator;

	private final PaymentProvider paymentProvider;

	public PaymentService(TransactionRepository transactionRepository, PaymentRuleValidator ruleValidator,
			PaymentProvider paymentProvider) {
		this.transactionRepository = transactionRepository;
		this.ruleValidator = ruleValidator;
		this.paymentProvider = paymentProvider;
	}

	@Transactional
	public PaymentResponse process(Merchant merchant, CreatePaymentRequest request) {
		ruleValidator.validate(request.paymentMethod(), request.currency(), request.amount());

		Transaction transaction = new Transaction();
		transaction.setMerchant(merchant);
		transaction.setAmount(request.amount());
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
		transaction.setProcessedAt(Instant.now());
		return PaymentResponse.from(transaction);
	}

}
