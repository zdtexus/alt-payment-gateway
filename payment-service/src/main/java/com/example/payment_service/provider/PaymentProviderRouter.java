package com.example.payment_service.provider;

import org.springframework.stereotype.Component;

import com.example.payment_service.entity.PaymentMethod;

@Component
public class PaymentProviderRouter {

	private final PaymentProvider defaultProvider;

	public PaymentProviderRouter(PaymentProviderRegistry registry, PaymentProviderProperties properties) {
		String name = properties.defaultProvider();
		this.defaultProvider = registry.find(name)
			.orElseThrow(() -> new IllegalStateException("payment.provider.default-provider (PAYMENT_PROVIDER_DEFAULT) "
					+ "names unknown payment provider '" + name + "'; available: " + registry.names()));
	}

	public PaymentProvider providerFor(PaymentMethod method) {
		return defaultProvider;
	}

}
