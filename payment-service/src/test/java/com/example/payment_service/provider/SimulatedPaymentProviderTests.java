package com.example.payment_service.provider;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.example.payment_service.entity.PaymentMethod;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentProviderTests {

	private static final ProviderRequest REQUEST = new ProviderRequest(UUID.randomUUID(), PaymentMethod.CARD, "EUR",
			new BigDecimal("10.00"));

	@RepeatedTest(50)
	void fullSuccessRateAlwaysApproves() {
		SimulatedPaymentProvider provider = provider(1.0, Duration.ZERO);

		assertThat(provider.process(REQUEST)).isInstanceOf(ProviderResult.Approved.class);
	}

	@RepeatedTest(50)
	void zeroSuccessRateAlwaysDeclinesWithReason() {
		SimulatedPaymentProvider provider = provider(0.0, Duration.ZERO);

		assertThat(provider.process(REQUEST)).isInstanceOfSatisfying(ProviderResult.Declined.class,
				(declined) -> assertThat(declined.reason()).isIn("Insufficient funds", "Suspected fraud",
						"Limit exceeded"));
	}

	@Test
	void everyResultHasItsOwnSimulatedReference() {
		SimulatedPaymentProvider provider = provider(0.5, Duration.ZERO);

		Set<String> references = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			references.add(switch (provider.process(REQUEST)) {
				case ProviderResult.Approved(String reference) -> reference;
				case ProviderResult.Declined(String reason, String reference) -> reference;
				case ProviderResult.Pending(String reference) -> reference;
			});
		}

		assertThat(references).hasSize(100).allMatch((reference) -> reference.matches("sim_[0-9a-f-]{36}"));
	}

	private static final RefundRequest REFUND = new RefundRequest(UUID.randomUUID(), UUID.randomUUID(), "sim_1", "EUR",
			new BigDecimal("4.00"));

	@RepeatedTest(20)
	void fullSuccessRateAlwaysApprovesRefunds() {
		assertThat(provider(1.0, Duration.ZERO).refund(REFUND)).isInstanceOfSatisfying(ProviderResult.Approved.class,
				(approved) -> assertThat(approved.reference()).matches("sim_r_[0-9a-f-]{36}"));
	}

	@RepeatedTest(20)
	void zeroSuccessRateAlwaysDeclinesRefundsWithARefundReason() {
		assertThat(provider(0.0, Duration.ZERO).refund(REFUND)).isInstanceOfSatisfying(ProviderResult.Declined.class,
				(declined) -> {
					assertThat(declined.reason()).isIn("Card account closed", "Refund window expired");
					assertThat(declined.reference()).matches("sim_r_[0-9a-f-]{36}");
				});
	}

	@Test
	void everyRefundHasItsOwnReferenceAndRefundsAreSupported() {
		SimulatedPaymentProvider provider = provider(0.5, Duration.ZERO);

		Set<String> references = new HashSet<>();
		for (int i = 0; i < 50; i++) {
			references.add(provider.refund(REFUND).reference());
		}

		assertThat(references).hasSize(50).allMatch((reference) -> reference.startsWith("sim_r_"));
		assertThat(provider.supportsRefunds()).isTrue();
	}

	@Test
	void refundsWaitForTheConfiguredLatencyToo() {
		SimulatedPaymentProvider provider = provider(1.0, Duration.ofMillis(100));

		long start = System.nanoTime();
		provider.refund(REFUND);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
	}

	private static final CaptureRequest CAPTURE = new CaptureRequest(UUID.randomUUID(), "sim_a_1", "EUR",
			new BigDecimal("6.00"));

	private static final VoidRequest VOID = new VoidRequest(UUID.randomUUID(), "sim_a_1");

	@RepeatedTest(20)
	void fullSuccessRateAuthorizesUntilTheValidityEnds() {
		SimulatedPaymentProvider provider = provider(1.0, Duration.ZERO, Duration.ofDays(3));

		Instant before = Instant.now();
		AuthorizationResult result = provider.authorize(REQUEST);
		Instant after = Instant.now();

		assertThat(result.result()).isInstanceOfSatisfying(ProviderResult.Approved.class,
				(approved) -> assertThat(approved.reference()).matches("sim_a_[0-9a-f-]{36}"));
		assertThat(result.expiresAt()).isBetween(before.plus(Duration.ofDays(3)), after.plus(Duration.ofDays(3)));
	}

	@RepeatedTest(20)
	void zeroSuccessRateDeclinesAuthorizationsWithoutExpiry() {
		AuthorizationResult result = provider(0.0, Duration.ZERO).authorize(REQUEST);

		assertThat(result.result()).isInstanceOfSatisfying(ProviderResult.Declined.class, (declined) -> {
			assertThat(declined.reason()).isIn("Insufficient funds", "Suspected fraud", "Limit exceeded");
			assertThat(declined.reference()).matches("sim_a_[0-9a-f-]{36}");
		});
		assertThat(result.expiresAt()).isNull();
	}

	@RepeatedTest(20)
	void capturesAndVoidsOfTheReservedMoneyAlwaysSucceed() {
		SimulatedPaymentProvider provider = provider(0.0, Duration.ZERO);

		assertThat(provider.capture(CAPTURE)).isInstanceOfSatisfying(ProviderResult.Approved.class,
				(approved) -> assertThat(approved.reference()).matches("sim_c_[0-9a-f-]{36}"));
		assertThat(provider.voidAuthorization(VOID)).isInstanceOfSatisfying(ProviderResult.Approved.class,
				(approved) -> assertThat(approved.reference()).matches("sim_v_[0-9a-f-]{36}"));
	}

	@Test
	void authorizationsCapturesAndVoidsWaitForTheConfiguredLatency() {
		SimulatedPaymentProvider provider = provider(1.0, Duration.ofMillis(100));

		long start = System.nanoTime();
		provider.authorize(REQUEST);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
		start = System.nanoTime();
		provider.capture(CAPTURE);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
		start = System.nanoTime();
		provider.voidAuthorization(VOID);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
	}

	@Test
	void supportsManualCaptureWithItsOwnReferences() {
		SimulatedPaymentProvider provider = provider(0.5, Duration.ZERO);

		Set<String> references = new HashSet<>();
		for (int i = 0; i < 20; i++) {
			references.add(provider.authorize(REQUEST).result().reference());
			references.add(provider.capture(CAPTURE).reference());
			references.add(provider.voidAuthorization(VOID).reference());
		}

		assertThat(references).hasSize(60);
		assertThat(provider.supportsManualCapture()).isTrue();
	}

	@Test
	void isNamedSimulated() {
		assertThat(provider(1.0, Duration.ZERO).name()).isEqualTo("simulated");
	}

	@Test
	void waitsForConfiguredLatency() {
		SimulatedPaymentProvider provider = provider(1.0, Duration.ofMillis(100));

		long start = System.nanoTime();
		provider.process(REQUEST);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
	}

	private static SimulatedPaymentProvider provider(double successRate, Duration latency) {
		return provider(successRate, latency, Duration.ofDays(7));
	}

	private static SimulatedPaymentProvider provider(double successRate, Duration latency,
			Duration authorizationValidity) {
		return new SimulatedPaymentProvider(
				new SimulatedPaymentProviderProperties(successRate, latency, authorizationValidity));
	}

}
