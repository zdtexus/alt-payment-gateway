package com.example.payment_service.provider;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

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
	void routesEveryMethodToTheDefaultProviderWithoutRoutes(PaymentMethod method) {
		PaymentProviderRouter router = router("other", Map.of());

		assertThat(router.providerFor(method)).isSameAs(other);
	}

	@Test
	void routeOverridesTheDefaultForItsMethodOnly() {
		PaymentProviderRouter router = router("simulated", Map.of(PaymentMethod.CARD, "other"));

		assertThat(router.providerFor(PaymentMethod.CARD)).isSameAs(other);
		assertThat(router.providerFor(PaymentMethod.BONUS)).isSameAs(simulated);
		assertThat(router.providerFor(PaymentMethod.CRYPTO)).isSameAs(simulated);
		assertThat(router.providerFor(PaymentMethod.KVIK)).isSameAs(simulated);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", " " })
	void withoutDefaultEveryMethodRoutedWorks(String defaultProvider) {
		PaymentProviderRouter router = router(defaultProvider, Map.of(PaymentMethod.CARD, "other", PaymentMethod.BONUS,
				"simulated", PaymentMethod.CRYPTO, "simulated", PaymentMethod.KVIK, "other"));

		assertThat(router.providerFor(PaymentMethod.CARD)).isSameAs(other);
		assertThat(router.providerFor(PaymentMethod.BONUS)).isSameAs(simulated);
		assertThat(router.providerFor(PaymentMethod.KVIK)).isSameAs(other);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", " " })
	void withoutDefaultAnUnroutedMethodFailsStartup(String defaultProvider) {
		assertThatIllegalStateException().isThrownBy(() -> router(defaultProvider,
				Map.of(PaymentMethod.CARD, "other", PaymentMethod.BONUS, "simulated", PaymentMethod.CRYPTO, "simulated")))
			.withMessage("KVIK has no payment provider: set payment.provider.routes.KVIK (PAYMENT_PROVIDER_ROUTES_KVIK) "
					+ "or payment.provider.default-provider (PAYMENT_PROVIDER_DEFAULT); "
					+ "available payment providers: [other, simulated]");
	}

	@Test
	void routeToUnknownProviderFailsStartup() {
		assertThatIllegalStateException()
			.isThrownBy(() -> router("simulated", Map.of(PaymentMethod.CRYPTO, "stripe")))
			.withMessage("payment.provider.routes.CRYPTO (PAYMENT_PROVIDER_ROUTES_CRYPTO) names unknown payment "
					+ "provider 'stripe'; available payment providers: [other, simulated]");
	}

	@Test
	void unknownDefaultProviderFailsStartup() {
		assertThatIllegalStateException().isThrownBy(() -> router("missing", Map.of()))
			.withMessage("payment.provider.default-provider (PAYMENT_PROVIDER_DEFAULT) names unknown payment "
					+ "provider 'missing'; available payment providers: [other, simulated]");
	}

	@Test
	void reportsEveryProblemAtOnce() {
		assertThatIllegalStateException()
			.isThrownBy(() -> router("missing", Map.of(PaymentMethod.CARD, "stripe", PaymentMethod.KVIK, "paypal")))
			.withMessageContaining("default-provider (PAYMENT_PROVIDER_DEFAULT) names unknown payment provider 'missing'")
			.withMessageContaining("routes.CARD (PAYMENT_PROVIDER_ROUTES_CARD) names unknown payment provider 'stripe'")
			.withMessageContaining("routes.KVIK (PAYMENT_PROVIDER_ROUTES_KVIK) names unknown payment provider 'paypal'");
	}

	private PaymentProviderRouter router(String defaultProvider, Map<PaymentMethod, String> routes) {
		return new PaymentProviderRouter(registry, new PaymentProviderProperties(defaultProvider, routes));
	}

}
