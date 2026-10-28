package com.example.payment_service.provider;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.provider.PaymentProviderRegistryTests.NamedProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PaymentProviderRouterTests {

	private final PaymentProvider simulated = new NamedProvider("simulated");

	private final PaymentProvider other = new NamedProvider("other");

	private final PaymentProviderRegistry registry = new PaymentProviderRegistry(List.of(simulated, other));

	@ParameterizedTest
	@EnumSource(PaymentMethod.class)
	void routesEveryMethodToTheDefaultProvider(PaymentMethod method) {
		PaymentProviderRouter router = new PaymentProviderRouter(registry, new PaymentProviderProperties("other"));

		assertThat(router.providerFor(method)).isSameAs(other);
	}

	@Test
	void unknownDefaultProviderFailsWithAvailableNames() {
		assertThatIllegalStateException()
			.isThrownBy(() -> new PaymentProviderRouter(registry, new PaymentProviderProperties("missing")))
			.withMessage("payment.provider.default-provider (PAYMENT_PROVIDER_DEFAULT) names unknown payment provider "
					+ "'missing'; available: [other, simulated]");
	}

}
