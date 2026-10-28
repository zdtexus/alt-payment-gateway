package com.example.payment_service.provider;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.payment_service.entity.PaymentMethod;

@Component
public class PaymentProviderRouter {

	private static final Logger log = LoggerFactory.getLogger(PaymentProviderRouter.class);

	private final Map<PaymentMethod, PaymentProvider> providers = new EnumMap<>(PaymentMethod.class);

	public PaymentProviderRouter(PaymentProviderRegistry registry, PaymentProviderProperties properties) {
		List<String> problems = new ArrayList<>();
		String defaultProvider = properties.defaultProvider();
		if (StringUtils.hasText(defaultProvider) && registry.find(defaultProvider).isEmpty()) {
			problems.add("payment.provider.default-provider (PAYMENT_PROVIDER_DEFAULT) names unknown payment provider '"
					+ defaultProvider + "'");
		}
		for (PaymentMethod method : PaymentMethod.values()) {
			String route = properties.routes().get(method);
			if (route != null) {
				Optional<PaymentProvider> provider = registry.find(route);
				provider.ifPresentOrElse((found) -> providers.put(method, found),
						() -> problems.add("payment.provider.routes." + method + " (PAYMENT_PROVIDER_ROUTES_" + method
								+ ") names unknown payment provider '" + route + "'"));
			}
			else if (StringUtils.hasText(defaultProvider)) {
				registry.find(defaultProvider).ifPresent((found) -> providers.put(method, found));
			}
			else {
				problems.add(method + " has no payment provider: set payment.provider.routes." + method
						+ " (PAYMENT_PROVIDER_ROUTES_" + method + ") or payment.provider.default-provider "
						+ "(PAYMENT_PROVIDER_DEFAULT)");
			}
		}
		if (!problems.isEmpty()) {
			throw new IllegalStateException(
					String.join("; ", problems) + "; available payment providers: " + registry.names());
		}
		log.info("Payment provider routes: {}", describe());
	}

	public PaymentProvider providerFor(PaymentMethod method) {
		return providers.get(method);
	}

	private String describe() {
		return providers.entrySet()
			.stream()
			.map((entry) -> entry.getKey() + "=" + entry.getValue().name())
			.collect(Collectors.joining(", "));
	}

}
