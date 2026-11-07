package com.example.payment_service.provider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

@Component
public class SimulatedPaymentProvider implements PaymentProvider, RefundingProvider, AuthorizingProvider {

	public static final String NAME = "simulated";

	static final String REFERENCE_PREFIX = "sim_";

	static final String REFUND_REFERENCE_PREFIX = "sim_r_";

	static final String AUTHORIZATION_REFERENCE_PREFIX = "sim_a_";

	static final String CAPTURE_REFERENCE_PREFIX = "sim_c_";

	static final String VOID_REFERENCE_PREFIX = "sim_v_";

	private static final List<String> DECLINE_REASONS = List.of("Insufficient funds", "Suspected fraud",
			"Limit exceeded");

	private static final List<String> REFUND_DECLINE_REASONS = List.of("Card account closed",
			"Refund window expired");

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

	@Override
	public boolean supportsRefunds() {
		return true;
	}

	@Override
	public ProviderResult refund(RefundRequest request) {
		simulateLatency();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		String reference = REFUND_REFERENCE_PREFIX + UUID.randomUUID();
		if (random.nextDouble() < properties.successRate()) {
			return ProviderResult.approved(reference);
		}
		return ProviderResult.declined(REFUND_DECLINE_REASONS.get(random.nextInt(REFUND_DECLINE_REASONS.size())),
				reference);
	}

	@Override
	public boolean supportsManualCapture() {
		return true;
	}

	@Override
	public AuthorizationResult authorize(ProviderRequest request) {
		simulateLatency();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		String reference = AUTHORIZATION_REFERENCE_PREFIX + UUID.randomUUID();
		if (random.nextDouble() < properties.successRate()) {
			return AuthorizationResult.approved(reference, Instant.now().plus(properties.authorizationValidity()));
		}
		return AuthorizationResult.declined(DECLINE_REASONS.get(random.nextInt(DECLINE_REASONS.size())), reference);
	}

	@Override
	public ProviderResult capture(CaptureRequest request) {
		simulateLatency();
		return ProviderResult.approved(CAPTURE_REFERENCE_PREFIX + UUID.randomUUID());
	}

	@Override
	public ProviderResult voidAuthorization(VoidRequest request) {
		simulateLatency();
		return ProviderResult.approved(VOID_REFERENCE_PREFIX + UUID.randomUUID());
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
