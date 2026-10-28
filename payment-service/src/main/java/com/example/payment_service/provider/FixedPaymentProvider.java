package com.example.payment_service.provider;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.example.payment_service.provider.FixedPaymentProviderProperties.Outcome;

@Component
public class FixedPaymentProvider implements PaymentProvider {

	public static final String NAME = "fixed";

	static final String REFERENCE_PREFIX = "fix_";

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

}
