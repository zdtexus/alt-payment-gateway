package com.example.payment_service.provider;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "payment.provider.routes.CRYPTO=fixed", "payment.provider.routes.KVIK=fixed",
		"PAYMENT_PROVIDER_FIXED_OUTCOME=decline", "PAYMENT_PROVIDER_FIXED_DECLINE_REASON=Routed to fixed",
		"PAYMENT_PROVIDER_SUCCESS_RATE=1.0", "PAYMENT_PROVIDER_LATENCY=0ms" })
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class PaymentProviderRoutingIntegrationTests {

	@Autowired
	private PaymentProviderRegistry registry;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private ApplicationEvents events;

	@AfterEach
	void deleteTransactions() {
		transactionRepository.deleteAll();
	}

	@Test
	void bothProvidersAreActive() {
		assertThat(registry.names()).containsExactly("fixed", "simulated");
	}

	@ParameterizedTest
	@CsvSource({ "CARD, simulated, sim_, SUCCEEDED, ", "BONUS, simulated, sim_, SUCCEEDED, ",
			"CRYPTO, fixed, fix_, DECLINED, Routed to fixed", "KVIK, fixed, fix_, DECLINED, Routed to fixed" })
	void eachMethodReachesItsRoutedProvider(PaymentMethod method, String provider, String referencePrefix,
			TransactionStatus status, String declineReason) {
		PaymentResponse response = paymentService.process(demoMerchant(),
				new CreatePaymentRequest(new BigDecimal("10.00"), "EUR", method));

		Transaction stored = transactionRepository.findById(response.id()).orElseThrow();
		assertThat(stored.getProvider()).isEqualTo(provider);
		assertThat(stored.getProviderReference()).startsWith(referencePrefix);
		assertThat(stored.getStatus()).isEqualTo(status);
		assertThat(stored.getDeclineReason()).isEqualTo(declineReason);
		assertThat(events.stream(PaymentProcessedEvent.class)).singleElement()
			.satisfies((event) -> assertThat(event.provider()).isEqualTo(provider));
	}

	private Merchant demoMerchant() {
		return merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("demo-key-123")).orElseThrow();
	}

}
