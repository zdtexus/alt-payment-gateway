package com.example.payment_service.refund;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.RefundStatus;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.error.PaymentNotRefundableException;
import com.example.payment_service.error.RefundExceedsRemainingException;
import com.example.payment_service.error.RefundNotSupportedException;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.SimulatedPaymentProvider;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.service.RefundOutcome;
import com.example.payment_service.service.RefundService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = { "PAYMENT_PROVIDER_SUCCESS_RATE=1.0", "PAYMENT_PROVIDER_LATENCY=0ms" })
class RefundServiceIntegrationTests {

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	private RefundService refundService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private SimulatedPaymentProvider simulatedProvider;

	private final ExecutorService executor = Executors.newFixedThreadPool(10);

	private Merchant merchant;

	private UUID payment;

	@BeforeEach
	void fiftyEuroSucceededPayment() {
		merchant = merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow();
		payment = payment(DEMO_MERCHANT_ID, "SUCCEEDED", "simulated");
	}

	@AfterEach
	void stopThreads() {
		executor.shutdownNow();
	}

	@Test
	void aRefundWithoutAmountRefundsEverything() {
		RefundOutcome outcome = refundService.create(merchant, payment, null, "Order cancelled", null);

		assertThat(outcome.refund().amount()).isEqualTo(new BigDecimal("50.00"));
		assertThat(outcome.refund().status()).isEqualTo(RefundStatus.SUCCEEDED);
		assertThat(jdbcTemplate.queryForMap("SELECT status, reason, provider_reference, next_check_at FROM refunds WHERE id = ?",
				outcome.refund().id()))
			.containsEntry("status", "SUCCEEDED")
			.containsEntry("reason", "Order cancelled")
			.containsEntry("next_check_at", null)
			.hasEntrySatisfying("provider_reference", (reference) -> assertThat((String) reference).startsWith("sim_r_"));
		assertThatExceptionOfType(RefundExceedsRemainingException.class)
			.isThrownBy(() -> refundService.create(merchant, payment, null, null, null))
			.withMessage("Payment is already fully refunded");
	}

	@Test
	void partialRefundsAddUpToThePaidAmountAndNoFurther() {
		refundService.create(merchant, payment, new BigDecimal("20.00"), null, null);
		refundService.create(merchant, payment, new BigDecimal("30.00"), null, null);

		assertThatExceptionOfType(RefundExceedsRemainingException.class)
			.isThrownBy(() -> refundService.create(merchant, payment, new BigDecimal("0.01"), null, null))
			.withMessage("Payment is already fully refunded");
		assertThat(refunded(payment)).isEqualTo(new BigDecimal("50.00"));
		assertThat(refundCount(payment)).isEqualTo(2);
	}

	@Test
	void anOverRefundIsRejectedAndNothingIsStored() {
		assertThatExceptionOfType(RefundExceedsRemainingException.class)
			.isThrownBy(() -> refundService.create(merchant, payment, new BigDecimal("50.01"), null, null))
			.withMessage("Refund 50.01 exceeds the remaining refundable amount 50.00");

		assertThat(refundCount(payment)).isZero();
	}

	@Test
	void anotherMerchantsPaymentIsNotFound() {
		UUID otherMerchant = merchant();
		UUID otherPayment = payment(otherMerchant, "SUCCEEDED", "simulated");

		assertThatExceptionOfType(PaymentNotFoundException.class)
			.isThrownBy(() -> refundService.create(merchant, otherPayment, null, null, null));
		assertThat(refundCount(otherPayment)).isZero();
	}

	@Test
	void aDeclinedPaymentCannotBeRefunded() {
		UUID declined = payment(DEMO_MERCHANT_ID, "DECLINED", "simulated");

		assertThatExceptionOfType(PaymentNotRefundableException.class)
			.isThrownBy(() -> refundService.create(merchant, declined, null, null, null));
	}

	@Test
	void aPaymentOfAProviderWithoutRefundsCannotBeRefunded() {
		UUID viaHttp = payment(DEMO_MERCHANT_ID, "SUCCEEDED", "http");

		assertThatExceptionOfType(RefundNotSupportedException.class)
			.isThrownBy(() -> refundService.create(merchant, viaHttp, null, null, null));
		assertThat(refundCount(viaHttp)).isZero();
	}

	@Test
	void aRetryWithTheSameKeyReturnsTheSameRefundAndCallsTheProviderOnce() {
		String key = "refund-" + UUID.randomUUID();

		RefundOutcome first = refundService.create(merchant, payment, new BigDecimal("10.00"), null, key);
		RefundOutcome retry = refundService.create(merchant, payment, new BigDecimal("10"), null, key);

		assertThat(first.replayed()).isFalse();
		assertThat(retry.replayed()).isTrue();
		assertThat(retry.refund().id()).isEqualTo(first.refund().id());
		assertThat(refundCount(payment)).isEqualTo(1);
		verify(simulatedProvider, times(1)).refund(any());
		assertThatExceptionOfType(IdempotencyKeyReusedException.class)
			.isThrownBy(() -> refundService.create(merchant, payment, new BigDecimal("11.00"), null, key));
	}

	@Test
	void aProviderFailureLeavesThePendingRefundReservingItsAmount() {
		willThrow(new IllegalStateException("provider down")).given(simulatedProvider).refund(any());

		RefundOutcome outcome = refundService.create(merchant, payment, new BigDecimal("10.00"), null, null);

		assertThat(outcome.refund().status()).isEqualTo(RefundStatus.PENDING);
		assertThat(jdbcTemplate.queryForObject("SELECT next_check_at IS NOT NULL FROM refunds WHERE id = ?",
				Boolean.class, outcome.refund().id())).isTrue();
		assertThatExceptionOfType(RefundExceedsRemainingException.class)
			.isThrownBy(() -> refundService.create(merchant, payment, new BigDecimal("40.01"), null, null))
			.withMessage("Refund 40.01 exceeds the remaining refundable amount 40.00");
	}

	@Test
	void aPendingAnswerAlsoReservesItsAmount() {
		willReturn(ProviderResult.pending("sim_r_waiting")).given(simulatedProvider).refund(any());
		refundService.create(merchant, payment, new BigDecimal("40.00"), null, null);
		willReturn(ProviderResult.approved("sim_r_ok")).given(simulatedProvider).refund(any());

		assertThatExceptionOfType(RefundExceedsRemainingException.class)
			.isThrownBy(() -> refundService.create(merchant, payment, new BigDecimal("20.00"), null, null))
			.withMessage("Refund 20.00 exceeds the remaining refundable amount 10.00");
		assertThat(refundService.create(merchant, payment, new BigDecimal("10.00"), null, null).refund().status())
			.isEqualTo(RefundStatus.SUCCEEDED);
	}

	@Test
	void concurrentRefundsNeverExceedThePaidAmount() throws Exception {
		CyclicBarrier start = new CyclicBarrier(10);
		List<Future<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			attempts.add(executor.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				try {
					refundService.create(merchant, payment, new BigDecimal("10.00"), null, null);
					return true;
				}
				catch (RefundExceedsRemainingException ex) {
					return false;
				}
			}));
		}

		int succeeded = 0;
		for (Future<Boolean> attempt : attempts) {
			succeeded += attempt.get(30, TimeUnit.SECONDS) ? 1 : 0;
		}

		assertThat(succeeded).isEqualTo(5);
		assertThat(refundCount(payment)).isEqualTo(5);
		assertThat(refunded(payment)).isEqualTo(new BigDecimal("50.00"));
	}

	@Test
	void concurrentRefundsNeverExceedTheCapturedAmount() throws Exception {
		UUID captured = manualPayment("SUCCEEDED", "30.00");
		CyclicBarrier start = new CyclicBarrier(10);
		List<Future<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			attempts.add(executor.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				try {
					refundService.create(merchant, captured, new BigDecimal("5.00"), null, null);
					return true;
				}
				catch (RefundExceedsRemainingException ex) {
					return false;
				}
			}));
		}

		int succeeded = 0;
		for (Future<Boolean> attempt : attempts) {
			succeeded += attempt.get(30, TimeUnit.SECONDS) ? 1 : 0;
		}

		assertThat(succeeded).isEqualTo(6);
		assertThat(refunded(captured)).isEqualTo(new BigDecimal("30.00"));
	}

	@Test
	void authorizedAndVoidedPaymentsCannotBeRefunded() {
		UUID authorized = manualPayment("AUTHORIZED", null);
		UUID voided = manualPayment("VOIDED", null);

		assertThatExceptionOfType(PaymentNotRefundableException.class)
			.isThrownBy(() -> refundService.create(merchant, authorized, null, null, null))
			.withMessageContaining("is AUTHORIZED; capture it before refunding it");
		assertThatExceptionOfType(PaymentNotRefundableException.class)
			.isThrownBy(() -> refundService.create(merchant, voided, null, null, null))
			.withMessageContaining("is VOIDED; only SUCCEEDED payments can be refunded");
		assertThat(refundCount(authorized) + refundCount(voided)).isZero();
	}

	private UUID manualPayment(String status, String captured) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider,
				                          provider_reference, capture_method, authorized_amount, captured_amount,
				                          authorization_expires_at)
				VALUES (?, ?, 50.00, 'EUR', 'CARD', ?, 'simulated', ?, 'MANUAL', 50.00, ?::numeric,
				        now() + interval '7 days')
				""", id, DEMO_MERCHANT_ID, status, "sim_a_" + id, captured);
		return id;
	}

	private UUID payment(UUID merchantId, String status, String provider) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider,
				                          provider_reference)
				VALUES (?, ?, 50.00, 'EUR', 'CARD', ?, ?, ?)
				""", id, merchantId, status, provider, "p_" + id);
		return id;
	}

	private UUID merchant() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO merchants (id, name, api_key_hash, status) VALUES (?, 'Other Shop', ?, 'ACTIVE')",
				id, id.toString().replace("-", "") + "0".repeat(32));
		return id;
	}

	private int refundCount(UUID paymentId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM refunds WHERE transaction_id = ?", Integer.class,
				paymentId);
	}

	private BigDecimal refunded(UUID paymentId) {
		return jdbcTemplate.queryForObject(
				"SELECT coalesce(sum(amount), 0) FROM refunds WHERE transaction_id = ? AND status = 'SUCCEEDED'",
				BigDecimal.class, paymentId);
	}

}
