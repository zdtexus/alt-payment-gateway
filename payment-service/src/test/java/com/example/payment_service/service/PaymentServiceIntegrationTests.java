package com.example.payment_service.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.PaymentRuleViolationException;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentServiceIntegrationTests {

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@MockitoBean
	private PaymentProvider paymentProvider;

	@AfterEach
	void deleteTransactions() {
		transactionRepository.deleteAll();
	}

	@Test
	void approvedPaymentIsStoredAsSucceeded() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());

		PaymentResponse response = paymentService.process(demoMerchant(), request("49.90", PaymentMethod.CARD));

		Transaction stored = transactionRepository.findById(response.id()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(stored.getDeclineReason()).isNull();
		assertThat(stored.getAmount()).isEqualByComparingTo("49.90");
		assertThat(stored.getCurrency()).isEqualTo("EUR");
		assertThat(stored.getCreatedAt()).isNotNull();
		assertThat(stored.getProcessedAt()).isNotNull();
	}

	@Test
	void declinedPaymentIsStoredWithReason() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.declined("Insufficient funds"));

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
	}

	@Test
	void providerFailureRollsBackPendingTransaction() {
		given(paymentProvider.process(any())).willThrow(new IllegalStateException("Provider unreachable"));

		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> paymentService.process(demoMerchant(), request("49.90", PaymentMethod.CARD)));

		assertThat(transactionRepository.count()).isZero();
	}

	private Merchant demoMerchant() {
		return merchantRepository.findByApiKey("demo-key-123").orElseThrow();
	}

	private static CreatePaymentRequest request(String amount, PaymentMethod method) {
		return new CreatePaymentRequest(new BigDecimal(amount), "EUR", method);
	}

}
