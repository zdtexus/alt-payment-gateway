package com.example.payment_service.reconciliation;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfig.class);

	@Test
	void firstCheckIsTwoMinutesAfterCreationByDefault() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer())
			.run((context) -> assertThat(context.getBean(ReconciliationProperties.class).minAge())
				.isEqualTo(Duration.ofMinutes(2)));
		runner.run((context) -> assertThat(context.getBean(ReconciliationProperties.class).minAge())
			.isEqualTo(Duration.ofMinutes(2)));
	}

	@Test
	void minAgeIsSetThroughItsEnvironmentVariable() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer())
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
						Map.of("PAYMENT_RECONCILIATION_MIN_AGE", "30s"))))
			.run((context) -> assertThat(context.getBean(ReconciliationProperties.class).minAge())
				.isEqualTo(Duration.ofSeconds(30)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0s", "-1m" })
	void minAgeMustBePositive(String minAge) {
		runner.withPropertyValues("payment.reconciliation.min-age=" + minAge)
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageStartingWith(
						"payment.reconciliation.min-age (PAYMENT_RECONCILIATION_MIN_AGE) must be greater than 0"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ReconciliationProperties.class)
	static class PropertiesConfig {

	}

}
