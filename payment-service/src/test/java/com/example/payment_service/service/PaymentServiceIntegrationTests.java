package com.example.payment_service.service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import com.example.payment_service.provider.ProviderRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
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

	@Autowired
	private DataSource dataSource;

	@AfterEach
	void deleteTransactions() {
		transactionRepository.deleteAll();
	}

	@Test
	void approvedPaymentIsStoredAsSucceeded() {
		willReturn(ProviderResult.approved("sim_ref-1")).given(paymentProvider).process(any());

		PaymentResponse response = paymentService.process(demoMerchant(), request("49.90", PaymentMethod.CARD));

		Transaction stored = transactionRepository.findById(response.id()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(stored.getProvider()).isEqualTo("simulated");
		assertThat(stored.getProviderReference()).isEqualTo("sim_ref-1");
		assertThat(stored.getDeclineReason()).isNull();
		assertThat(stored.getAmount()).isEqualByComparingTo("49.90");
		assertThat(stored.getCurrency()).isEqualTo("EUR");
		assertThat(stored.getCreatedAt()).isNotNull();
		assertThat(stored.getProcessedAt()).isNotNull();
		assertThat(events.stream(PaymentProcessedEvent.class)).singleElement().satisfies((event) -> {
			assertThat(event.paymentId()).isEqualTo(response.id());
			assertThat(event.status()).isEqualTo(TransactionStatus.SUCCEEDED);
			assertThat(event.merchantName()).isEqualTo("Demo Shop");
			assertThat(event.provider()).isEqualTo("simulated");
		});
	}

	@Test
	void declinedPaymentIsStoredWithReason() {
		willReturn(ProviderResult.declined("Insufficient funds", "sim_ref-2")).given(paymentProvider).process(any());

		PaymentResponse response = paymentService.process(demoMerchant(), request("49.90", PaymentMethod.CARD));

		Transaction stored = transactionRepository.findById(response.id()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.DECLINED);
		assertThat(stored.getProvider()).isEqualTo("simulated");
		assertThat(stored.getProviderReference()).isEqualTo("sim_ref-2");
		assertThat(stored.getDeclineReason()).isEqualTo("Insufficient funds");
		assertThat(stored.getProcessedAt()).isNotNull();
	}

	@Test
	void pendingPaymentIsStoredAsPendingWithoutEvent() {
		willReturn(ProviderResult.pending("sim_ref-3")).given(paymentProvider).process(any());

		PaymentResponse response = paymentService.process(demoMerchant(), request("49.90", PaymentMethod.CARD));

		Transaction stored = transactionRepository.findById(response.id()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.PENDING);
		assertThat(stored.getProvider()).isEqualTo("simulated");
		assertThat(stored.getProviderReference()).isEqualTo("sim_ref-3");
		assertThat(stored.getDeclineReason()).isNull();
		assertThat(stored.getProcessedAt()).isNull();
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void ruleViolationStoresNothing() {
		assertThatExceptionOfType(PaymentRuleViolationException.class)
			.isThrownBy(() -> paymentService.process(demoMerchant(), request("600.00", PaymentMethod.BONUS)));

		assertThat(transactionRepository.count()).isZero();
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void providerFailureLeavesCommittedPendingPayment() {
		willThrow(new IllegalStateException("Provider unreachable")).given(paymentProvider).process(any());

		PaymentResponse response = paymentService.process(demoMerchant(), request("49.90", PaymentMethod.CARD));

		assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);
		Transaction stored = transactionRepository.findById(response.id()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.PENDING);
		assertThat(stored.getProvider()).isEqualTo("simulated");
		assertThat(stored.getProviderReference()).isNull();
		assertThat(stored.getProcessedAt()).isNull();
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void providerIsCalledOutsideTransactionAfterPendingRowIsCommitted() {
		AtomicBoolean transactionActive = new AtomicBoolean(true);
		AtomicReference<TransactionStatus> committedStatus = new AtomicReference<>();
		willAnswer((invocation) -> {
			ProviderRequest providerRequest = invocation.getArgument(0);
			transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
			committedStatus.set(new JdbcTemplate(dataSource).queryForObject(
					"SELECT status FROM transactions WHERE id = ?", TransactionStatus.class,
					providerRequest.paymentId()));
			return ProviderResult.approved();
		}).given(paymentProvider).process(any());

		PaymentResponse response = paymentService.process(demoMerchant(), request("49.90", PaymentMethod.CARD));

		assertThat(transactionActive).isFalse();
		assertThat(committedStatus).hasValue(TransactionStatus.PENDING);
		assertThat(response.status()).isEqualTo(TransactionStatus.SUCCEEDED);
	}

	private Merchant demoMerchant() {
		return merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("demo-key-123")).orElseThrow();
	}

	private static CreatePaymentRequest request(String amount, PaymentMethod method) {
		return new CreatePaymentRequest(new BigDecimal(amount), "EUR", method);
	}

}
