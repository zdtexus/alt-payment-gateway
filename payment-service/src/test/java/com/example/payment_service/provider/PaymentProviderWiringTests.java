package com.example.payment_service.provider;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.provider.PaymentProviderRegistryTests.NamedProvider;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProviderWiringTests {

	@Nested
	@SpringBootTest(classes = ProviderConfig.class,
			properties = { "PAYMENT_PROVIDER_SUCCESS_RATE=0.25", "PAYMENT_PROVIDER_LATENCY=0ms" })
	class WithDefaults {

		@Autowired
		private PaymentProviderRegistry registry;

		@Autowired
		private PaymentProviderRouter router;

		@Autowired
		private SimulatedPaymentProviderProperties simulatedProperties;

		@Test
		void twoProviderBeansCanBeActiveAtOnce() {
			assertThat(registry.names()).containsExactly("second", "simulated");
		}

		@Test
		void routesToSimulatedByDefault() {
			assertThat(router.providerFor(PaymentMethod.CARD)).isInstanceOf(SimulatedPaymentProvider.class);
		}

		@Test
		void oldEnvironmentVariableNamesStillConfigureTheSimulatedProvider() {
			assertThat(simulatedProperties.successRate()).isEqualTo(0.25);
			assertThat(simulatedProperties.latency()).isEqualTo(Duration.ZERO);
		}

	}

	@Nested
	@SpringBootTest(classes = ProviderConfig.class, properties = "PAYMENT_PROVIDER_DEFAULT=second")
	class WithDefaultProviderSetByEnvironment {

		@Autowired
		private PaymentProviderRouter router;

		@Test
		void routesToTheConfiguredDefault() {
			assertThat(router.providerFor(PaymentMethod.CARD).name()).isEqualTo("second");
		}

	}

	@Test
	void startupFailsWhenDefaultProviderIsUnknown() {
		new ApplicationContextRunner().withUserConfiguration(ProviderConfig.class)
			.withPropertyValues("payment.provider.default-provider=missing")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("payment.provider.default-provider (PAYMENT_PROVIDER_DEFAULT) names unknown payment "
						+ "provider 'missing'; available payment providers: [second, simulated]"));
	}

	@Test
	void routesFromEnvironmentVariablesSendEachMethodToItsProvider() {
		new ApplicationContextRunner().withUserConfiguration(ProviderConfig.class)
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
						Map.of("PAYMENT_PROVIDER_ROUTES_KVIK", "second", "PAYMENT_PROVIDER_ROUTES_CARD", "simulated"))))
			.run((context) -> {
				PaymentProviderRouter router = context.getBean(PaymentProviderRouter.class);
				assertThat(router.providerFor(PaymentMethod.KVIK).name()).isEqualTo("second");
				assertThat(router.providerFor(PaymentMethod.CARD).name()).isEqualTo("simulated");
				assertThat(router.providerFor(PaymentMethod.BONUS).name()).isEqualTo("simulated");
			});
	}

	@Test
	void routeKeysIgnoreCase() {
		new ApplicationContextRunner().withUserConfiguration(ProviderConfig.class)
			.withPropertyValues("payment.provider.routes.crypto=second")
			.run((context) -> assertThat(context.getBean(PaymentProviderRouter.class)
				.providerFor(PaymentMethod.CRYPTO)
				.name()).isEqualTo("second"));
	}

	@Test
	void startupFailsWhenRouteNamesUnknownProvider() {
		new ApplicationContextRunner().withUserConfiguration(ProviderConfig.class)
			.withPropertyValues("payment.provider.routes.CARD=stripe")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("payment.provider.routes.CARD (PAYMENT_PROVIDER_ROUTES_CARD) names unknown payment "
						+ "provider 'stripe'; available payment providers: [second, simulated]"));
	}

	@Test
	void startupFailsWhenRouteNamesUnknownPaymentMethod() {
		new ApplicationContextRunner().withUserConfiguration(ProviderConfig.class)
			.withPropertyValues("payment.provider.routes.PAYPAL=simulated")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.hasStackTraceContaining("payment.provider.routes")
				.hasStackTraceContaining("PAYPAL"));
	}

	@Test
	void startupFailsWithoutDefaultWhenAMethodHasNoRoute() {
		new ApplicationContextRunner().withUserConfiguration(ProviderConfig.class)
			.withPropertyValues("payment.provider.default-provider=", "payment.provider.routes.CARD=simulated")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageStartingWith("BONUS has no payment provider")
				.hasMessageContaining("CRYPTO has no payment provider")
				.hasMessageContaining("KVIK has no payment provider"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties({ PaymentProviderProperties.class, SimulatedPaymentProviderProperties.class })
	@Import({ SimulatedPaymentProvider.class, PaymentProviderRegistry.class, PaymentProviderRouter.class })
	static class ProviderConfig {

		@Bean
		PaymentProvider secondProvider() {
			return new NamedProvider("second");
		}

	}

}
