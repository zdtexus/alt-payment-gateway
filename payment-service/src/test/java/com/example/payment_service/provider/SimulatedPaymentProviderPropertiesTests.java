package com.example.payment_service.provider;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentProviderPropertiesTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(Config.class);

	@Test
	void usesDefaultsWhenNotConfigured() {
		contextRunner.run((context) -> {
			SimulatedPaymentProviderProperties properties = context.getBean(SimulatedPaymentProviderProperties.class);
			assertThat(properties.successRate()).isEqualTo(0.9);
			assertThat(properties.latency()).isEqualTo(Duration.ofMillis(300));
			assertThat(properties.authorizationValidity()).isEqualTo(Duration.ofDays(7));
		});
	}

	@Test
	void bindsConfiguredValues() {
		contextRunner
			.withPropertyValues("payment.provider.simulated.success-rate=0.25", "payment.provider.simulated.latency=2s",
					"payment.provider.simulated.authorization-validity=3d")
			.run((context) -> {
				SimulatedPaymentProviderProperties properties = context
					.getBean(SimulatedPaymentProviderProperties.class);
				assertThat(properties.successRate()).isEqualTo(0.25);
				assertThat(properties.latency()).isEqualTo(Duration.ofSeconds(2));
				assertThat(properties.authorizationValidity()).isEqualTo(Duration.ofDays(3));
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "-0.1", "1.5" })
	void rejectsSuccessRateOutsideZeroToOne(String successRate) {
		contextRunner.withPropertyValues("payment.provider.simulated.success-rate=" + successRate)
			.run((context) -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = { "0s", "-1h" })
	void rejectsAnAuthorizationValidityThatIsNotPositive(String validity) {
		contextRunner.withPropertyValues("payment.provider.simulated.authorization-validity=" + validity)
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.hasStackTraceContaining("payment.provider.simulated.authorization-validity must be positive"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(SimulatedPaymentProviderProperties.class)
	static class Config {

	}

}
