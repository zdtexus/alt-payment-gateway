package com.example.payment_service.provider;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProviderPropertiesTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(Config.class);

	@Test
	void usesDefaultsWhenNotConfigured() {
		contextRunner.run((context) -> {
			PaymentProviderProperties properties = context.getBean(PaymentProviderProperties.class);
			assertThat(properties.successRate()).isEqualTo(0.9);
			assertThat(properties.latency()).isEqualTo(Duration.ofMillis(300));
		});
	}

	@Test
	void bindsConfiguredValues() {
		contextRunner.withPropertyValues("payment.provider.success-rate=0.25", "payment.provider.latency=2s")
			.run((context) -> {
				PaymentProviderProperties properties = context.getBean(PaymentProviderProperties.class);
				assertThat(properties.successRate()).isEqualTo(0.25);
				assertThat(properties.latency()).isEqualTo(Duration.ofSeconds(2));
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "-0.1", "1.5" })
	void rejectsSuccessRateOutsideZeroToOne(String successRate) {
		contextRunner.withPropertyValues("payment.provider.success-rate=" + successRate)
			.run((context) -> assertThat(context).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(PaymentProviderProperties.class)
	static class Config {

	}

}
