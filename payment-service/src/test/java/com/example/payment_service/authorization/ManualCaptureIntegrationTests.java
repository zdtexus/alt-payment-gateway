package com.example.payment_service.authorization;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.CaptureMethod;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.ManualCaptureNotSupportedException;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.AuthorizationResult;
import com.example.payment_service.provider.SimulatedPaymentProvider;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.PaymentOutcome;
import com.example.payment_service.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class ManualCaptureIntegrationTests {

	private static final String KEY = "order-2001";

	private static final Instant EXPIRES = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

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
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void deleteTransactions() {
		transactionRepository.deleteAll();
	}

	@Test
	void anAuthorizedPaymentIsStoredWithItsAuthorizationAndNoReceipt() {
		willReturn(AuthorizationResult.approved("sim_a_1", EXPIRES)).given(paymentProvider).authorize(any());

		PaymentResponse response = paymentService.process(demoMerchant(), manual("49.90"));

		assertThat(response.status()).isEqualTo(TransactionStatus.AUTHORIZED);
		Map<String, Object> row = jdbcTemplate.queryForMap("""
				SELECT status, capture_method, authorized_amount, captured_amount, authorization_expires_at,
				       provider, provider_reference, processed_at, next_check_at
				FROM transactions WHERE id = ?
				""", response.id());
		assertThat(row).containsEntry("status", "AUTHORIZED")
			.containsEntry("capture_method", "MANUAL")
			.containsEntry("authorized_amount", new BigDecimal("49.90"))
			.containsEntry("captured_amount", null)
			.containsEntry("provider", "simulated")
			.containsEntry("provider_reference", "sim_a_1")
			.containsEntry("processed_at", null)
			.containsEntry("next_check_at", null);
		assertThat(((Timestamp) row.get("authorization_expires_at")).toInstant()).isEqualTo(EXPIRES);
		assertThat(paymentService.get(demoMerchant(), response.id())).isEqualTo(response);
		then(paymentProvider).should(never()).process(any());
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void aDeclinedAuthorizationIsDeclinedWithOneReceipt() {
		willReturn(AuthorizationResult.declined("Insufficient funds", "sim_a_1")).given(paymentProvider)
			.authorize(any());

		PaymentResponse response = paymentService.process(demoMerchant(), manual("49.90"));

		assertThat(response.status()).isEqualTo(TransactionStatus.DECLINED);
		assertThat(response.declineReason()).isEqualTo("Insufficient funds");
		assertThat(events.stream(PaymentProcessedEvent.class))
			.singleElement()
			.satisfies((event) -> assertThat(event.status()).isEqualTo(TransactionStatus.DECLINED));
	}

	@Test
	void aPendingAuthorizationStaysPendingAndDue() {
		willReturn(AuthorizationResult.pending("sim_a_1")).given(paymentProvider).authorize(any());

		PaymentResponse response = paymentService.process(demoMerchant(), manual("49.90"));

		assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT provider_reference, authorized_amount, next_check_at FROM transactions WHERE id = ?",
				response.id());
		assertThat(row).containsEntry("provider_reference", "sim_a_1").containsEntry("authorized_amount", null);
		assertThat(row.get("next_check_at")).isNotNull();
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void aRetriedManualPaymentIsReplayedWithoutAuthorizingAgain() {
		willReturn(AuthorizationResult.approved("sim_a_1", EXPIRES)).given(paymentProvider).authorize(any());

		PaymentOutcome first = paymentService.process(demoMerchant(), manual("49.90"), KEY);
		PaymentOutcome retry = paymentService.process(demoMerchant(), manual("49.90"), KEY);

		assertThat(retry.replayed()).isTrue();
		assertThat(retry.payment()).isEqualTo(first.payment());
		assertThat(retry.payment().status()).isEqualTo(TransactionStatus.AUTHORIZED);
		assertThat(transactionRepository.count()).isEqualTo(1);
		then(paymentProvider).should(times(1)).authorize(any());
	}

	@Test
	void theSameKeyCannotSwitchBetweenCaptureMethods() {
		willReturn(AuthorizationResult.approved("sim_a_1", EXPIRES)).given(paymentProvider).authorize(any());
		paymentService.process(demoMerchant(), manual("49.90"), KEY);

		assertThatExceptionOfType(IdempotencyKeyReusedException.class).isThrownBy(() -> paymentService
			.process(demoMerchant(), new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD), KEY));
		then(paymentProvider).should(never()).process(any());
	}

	@Test
	void aProviderWithoutManualCaptureStoresNothingAndKeepsTheKeyFree() {
		willReturn(false).given(paymentProvider).supportsManualCapture();

		assertThatExceptionOfType(ManualCaptureNotSupportedException.class)
			.isThrownBy(() -> paymentService.process(demoMerchant(), manual("49.90"), KEY))
			.withMessage("Manual capture is not supported for CARD payments");

		assertThat(transactionRepository.count()).isZero();
		then(paymentProvider).should(never()).authorize(any());
		willReturn(true).given(paymentProvider).supportsManualCapture();
		willReturn(AuthorizationResult.approved("sim_a_1", EXPIRES)).given(paymentProvider).authorize(any());
		assertThat(paymentService.process(demoMerchant(), manual("49.90"), KEY).replayed()).isFalse();
	}

	private Merchant demoMerchant() {
		return merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("demo-key-123")).orElseThrow();
	}

	private static CreatePaymentRequest manual(String amount) {
		return new CreatePaymentRequest(new BigDecimal(amount), "EUR", PaymentMethod.CARD, CaptureMethod.MANUAL);
	}

}
