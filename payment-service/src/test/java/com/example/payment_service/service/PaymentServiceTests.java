package com.example.payment_service.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.PaymentRuleViolationException;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.ProviderRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.rules.PaymentRuleValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentServiceTests {

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	private static final CreatePaymentRequest REQUEST = new CreatePaymentRequest(new BigDecimal("49.90"), "EUR",
			PaymentMethod.CARD);

	private final TransactionRepository transactionRepository = mock();

	private final PaymentRuleValidator ruleValidator = mock();

	private final PaymentProvider paymentProvider = mock();

	private final PaymentService paymentService = new PaymentService(transactionRepository, ruleValidator,
			paymentProvider);

	private final Merchant merchant = new Merchant();

	private final AtomicReference<TransactionStatus> statusWhenSaved = new AtomicReference<>();

	@BeforeEach
	void assignIdOnSave() {
		given(transactionRepository.save(any())).willAnswer((invocation) -> {
			Transaction transaction = invocation.getArgument(0);
			statusWhenSaved.set(transaction.getStatus());
			transaction.setId(PAYMENT_ID);
			return transaction;
		});
	}

	@Test
	void approvedPaymentSucceeds() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());
		Instant before = Instant.now();

		PaymentResponse response = paymentService.process(merchant, REQUEST);

		assertThat(response.id()).isEqualTo(PAYMENT_ID);
		assertThat(response.status()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(response.declineReason()).isNull();
		assertThat(response.processedAt()).isBetween(before, Instant.now());
		assertThat(response.amount()).isEqualByComparingTo("49.90");
		assertThat(response.currency()).isEqualTo("EUR");
		assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.CARD);
	}

	@Test
	void declinedPaymentKeepsReason() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.declined("Insufficient funds"));

		PaymentResponse response = paymentService.process(merchant, REQUEST);

		assertThat(response.status()).isEqualTo(TransactionStatus.DECLINED);
		assertThat(response.declineReason()).isEqualTo("Insufficient funds");
		assertThat(response.processedAt()).isNotNull();
	}

	@Test
	void savesPendingTransactionForMerchantBeforeCallingProvider() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());

		paymentService.process(merchant, REQUEST);

		ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
		verify(transactionRepository).save(saved.capture());
		assertThat(statusWhenSaved).hasValue(TransactionStatus.PENDING);
		assertThat(saved.getValue().getMerchant()).isSameAs(merchant);
		verify(paymentProvider)
			.process(new ProviderRequest(PAYMENT_ID, PaymentMethod.CARD, "EUR", new BigDecimal("49.90")));
	}

	@Test
	void ruleViolationStopsBeforeSavingOrCallingProvider() {
		willThrow(new PaymentRuleViolationException("Amount 49.90 exceeds BONUS limit 500.00")).given(ruleValidator)
			.validate(PaymentMethod.CARD, "EUR", new BigDecimal("49.90"));

		assertThatExceptionOfType(PaymentRuleViolationException.class)
			.isThrownBy(() -> paymentService.process(merchant, REQUEST));
		verifyNoInteractions(transactionRepository, paymentProvider);
	}

}
