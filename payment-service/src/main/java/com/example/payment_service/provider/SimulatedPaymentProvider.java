package com.example.payment_service.provider;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

@Component
public class SimulatedPaymentProvider implements PaymentProvider {

	private static final List<String> DECLINE_REASONS = List.of("Insufficient funds", "Suspected fraud",
			"Limit exceeded");

	private final PaymentProviderProperties properties;

	public SimulatedPaymentProvider(PaymentProviderProperties properties) {
		this.properties = properties;
	}

	@Override
	public ProviderResult process(ProviderRequest request) {
		simulateLatency();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		if (random.nextDouble() < properties.successRate()) {
			return ProviderResult.approved();
		}
		return ProviderResult.declined(DECLINE_REASONS.get(random.nextInt(DECLINE_REASONS.size())));
	}

	private void simulateLatency() {
		if (properties.latency().isZero()) {
			return;
		}
		try {
			Thread.sleep(properties.latency());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
