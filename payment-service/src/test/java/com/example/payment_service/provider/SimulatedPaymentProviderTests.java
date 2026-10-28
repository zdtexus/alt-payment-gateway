package com.example.payment_service.provider;

import java.math.BigDecimal;
import java.time.Duration;
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
			});
		}

		assertThat(references).hasSize(100).allMatch((reference) -> reference.matches("sim_[0-9a-f-]{36}"));
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
		return new SimulatedPaymentProvider(new SimulatedPaymentProviderProperties(successRate, latency));
	}

}
