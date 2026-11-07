package com.example.payment_service.provider;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.example.payment_service.provider.FixedPaymentProviderProperties.Outcome;

@Component
public class FixedPaymentProvider implements PaymentProvider, RefundingProvider, AuthorizingProvider {

	public static final String NAME = "fixed";

	static final String REFERENCE_PREFIX = "fix_";

	static final String REFUND_REFERENCE_PREFIX = "fix_r_";

	static final String AUTHORIZATION_REFERENCE_PREFIX = "fix_a_";

	static final String CAPTURE_REFERENCE_PREFIX = "fix_c_";

	static final String VOID_REFERENCE_PREFIX = "fix_v_";

	private final FixedPaymentProviderProperties properties;

	public FixedPaymentProvider(FixedPaymentProviderProperties properties) {
		this.properties = properties;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public ProviderResult process(ProviderRequest request) {
		String reference = REFERENCE_PREFIX + UUID.randomUUID();
		if (properties.outcome() == Outcome.APPROVE) {
			return ProviderResult.approved(reference);
		}
		return ProviderResult.declined(properties.declineReason(), reference);
	}

	@Override
	public boolean supportsRefunds() {
		return true;
	}

	@Override
	public ProviderResult refund(RefundRequest request) {
		return outcome(REFUND_REFERENCE_PREFIX);
	}

	@Override
	public boolean supportsManualCapture() {
		return true;
	}

	@Override
	public AuthorizationResult authorize(ProviderRequest request) {
		String reference = AUTHORIZATION_REFERENCE_PREFIX + UUID.randomUUID();
		if (properties.outcome() == Outcome.APPROVE) {
			return AuthorizationResult.approved(reference, Instant.now().plus(properties.authorizationValidity()));
		}
		return AuthorizationResult.declined(properties.declineReason(), reference);
	}

	@Override
	public ProviderResult capture(CaptureRequest request) {
		return outcome(CAPTURE_REFERENCE_PREFIX);
	}

	@Override
	public ProviderResult voidAuthorization(VoidRequest request) {
		return outcome(VOID_REFERENCE_PREFIX);
	}

	private ProviderResult outcome(String referencePrefix) {
		String reference = referencePrefix + UUID.randomUUID();
		if (properties.outcome() == Outcome.APPROVE) {
			return ProviderResult.approved(reference);
		}
		return ProviderResult.declined(properties.declineReason(), reference);
	}

}
