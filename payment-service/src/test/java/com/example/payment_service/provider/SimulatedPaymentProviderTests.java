package com.example.payment_service.provider;

import java.math.BigDecimal;
import java.time.Duration;
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
	void waitsForConfiguredLatency() {
		SimulatedPaymentProvider provider = provider(1.0, Duration.ofMillis(100));

		long start = System.nanoTime();
		provider.process(REQUEST);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
	}

	private static SimulatedPaymentProvider provider(double successRate, Duration latency) {
		return new SimulatedPaymentProvider(new PaymentProviderProperties(successRate, latency));
	}

}
