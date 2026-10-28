package com.example.payment_service.provider;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

@Component
public class SimulatedPaymentProvider implements PaymentProvider {

	public static final String NAME = "simulated";

	static final String REFERENCE_PREFIX = "sim_";

	private static final List<String> DECLINE_REASONS = List.of("Insufficient funds", "Suspected fraud",
			"Limit exceeded");

	private final SimulatedPaymentProviderProperties properties;

	public SimulatedPaymentProvider(SimulatedPaymentProviderProperties properties) {
		this.properties = properties;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public ProviderResult process(ProviderRequest request) {
		simulateLatency();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		String reference = REFERENCE_PREFIX + UUID.randomUUID();
		if (random.nextDouble() < properties.successRate()) {
			return ProviderResult.approved(reference);
		}
		return ProviderResult.declined(DECLINE_REASONS.get(random.nextInt(DECLINE_REASONS.size())), reference);
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
