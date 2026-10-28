package com.example.payment_service.provider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PaymentProviderRegistry {

	private final Map<String, PaymentProvider> providers = new TreeMap<>();

	public PaymentProviderRegistry(List<PaymentProvider> providers) {
		for (PaymentProvider provider : providers) {
			String name = provider.name();
			if (!StringUtils.hasText(name)) {
				throw new IllegalStateException(
						"Payment provider " + provider.getClass().getName() + " must have a non-blank name()");
			}
			PaymentProvider existing = this.providers.putIfAbsent(name, provider);
			if (existing != null) {
				throw new IllegalStateException("Two payment providers are named '" + name + "': "
						+ existing.getClass().getName() + " and " + provider.getClass().getName());
			}
		}
		if (this.providers.isEmpty()) {
			throw new IllegalStateException("No payment provider is available");
		}
	}

	public Optional<PaymentProvider> find(String name) {
		return Optional.ofNullable(providers.get(name));
	}

	public Optional<WebhookProvider> findWebhookProvider(String name) {
		return find(name).filter(WebhookProvider.class::isInstance)
			.map(WebhookProvider.class::cast)
			.filter(WebhookProvider::acceptsWebhooks);
	}

	public Set<String> names() {
		return providers.keySet();
	}

}
