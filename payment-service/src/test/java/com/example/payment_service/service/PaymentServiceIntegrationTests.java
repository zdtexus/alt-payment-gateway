package com.example.payment_service.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.PaymentRuleViolationException;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.SimulatedPaymentProvider;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class PaymentServiceIntegrationTests {

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@MockitoSpyBean
	private SimulatedPaymentProvider paymentProvider;

	@Autowired
	private ApplicationEvents events;

	@AfterEach
	void deleteTransactions() {
		transactionRepository.deleteAll();
	}

	@Test
	void approvedPaymentIsStoredAsSucceeded() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());

		PaymentResponse response = paymentService.process(demoMerchant(), request("49.90", PaymentMethod.CARD));

		Transaction stored = transactionRepository.findById(response.id()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(stored.getDeclineReason()).isNull();
		assertThat(stored.getAmount()).isEqualByComparingTo("49.90");
		assertThat(stored.getCurrency()).isEqualTo("EUR");
		assertThat(stored.getCreatedAt()).isNotNull();
		assertThat(stored.getProcessedAt()).isNotNull();
		assertThat(events.stream(PaymentProcessedEvent.class)).singleElement().satisfies((event) -> {
			assertThat(event.paymentId()).isEqualTo(response.id());
			assertThat(event.status()).isEqualTo(TransactionStatus.SUCCEEDED);
			assertThat(event.merchantName()).isEqualTo("Demo Shop");
		});
	}

	@Test
	void declinedPaymentIsStoredWithReason() {
		willReturn(ProviderResult.declined("Insufficient funds")).given(paymentProvider).process(any());

		PaymentResponse response = paymentService.process(demoMerchant(), request("49.90", PaymentMethod.CARD));

		Transaction stored = transactionRepository.findById(response.id()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.DECLINED);
		assertThat(stored.getDeclineReason()).isEqualTo("Insufficient funds");
		assertThat(stored.getProcessedAt()).isNotNull();
	}

	@Test
	void ruleViolationStoresNothing() {
		assertThatExceptionOfType(PaymentRuleViolationException.class)
			.isThrownBy(() -> paymentService.process(demoMerchant(), request("600.00", PaymentMethod.BONUS)));

		assertThat(transactionRepository.count()).isZero();
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void providerFailureRollsBackPendingTransaction() {
		willThrow(new IllegalStateException("Provider unreachable")).given(paymentProvider).process(any());

		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> paymentService.process(demoMerchant(), request("49.90", PaymentMethod.CARD)));

		assertThat(transactionRepository.count()).isZero();
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	private Merchant demoMerchant() {
		return merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("demo-key-123")).orElseThrow();
	}

	private static CreatePaymentRequest request(String amount, PaymentMethod method) {
		return new CreatePaymentRequest(new BigDecimal(amount), "EUR", method);
	}

}
