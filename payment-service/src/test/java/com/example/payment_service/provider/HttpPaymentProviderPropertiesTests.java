package com.example.payment_service.provider;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class HttpPaymentProviderPropertiesTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(Config.class);

	@Test
	void usesDefaultTimeouts() {
		contextRunner.run((context) -> {
			HttpPaymentProviderProperties properties = context.getBean(HttpPaymentProviderProperties.class);
			assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
			assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
		});
	}

	@Test
	void bindsConfiguredValues() {
		contextRunner
			.withPropertyValues("payment.provider.http.url=https://pay.example.com/charge",
					"payment.provider.http.api-key=secret", "payment.provider.http.connect-timeout=500ms",
					"payment.provider.http.read-timeout=3s")
			.run((context) -> {
				HttpPaymentProviderProperties properties = context.getBean(HttpPaymentProviderProperties.class);
				assertThat(properties.url()).isEqualTo(URI.create("https://pay.example.com/charge"));
				assertThat(properties.apiKey()).isEqualTo("secret");
				assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(500));
				assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
			});
	}

	@Test
	void providerIsNotRegisteredWithoutUrl() {
		contextRunner.run((context) -> assertThat(context).doesNotHaveBean(HttpPaymentProvider.class));
	}

	@Test
	void providerIsRegisteredWhenUrlIsSet() {
		contextRunner.withPropertyValues("payment.provider.http.url=https://pay.example.com/charge")
			.run((context) -> assertThat(context).hasSingleBean(HttpPaymentProvider.class));
	}

	@Test
	void providerIsRegisteredFromEnvironmentVariable() {
		contextRunner
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
						Map.of("PAYMENT_PROVIDER_HTTP_URL", "https://pay.example.com/charge"))))
			.run((context) -> assertThat(context).hasSingleBean(HttpPaymentProvider.class));
	}

	@Test
	void webhookSecretIsOptional() {
		contextRunner.run((context) -> assertThat(context.getBean(HttpPaymentProviderProperties.class).webhookSecret())
			.isNull());
	}

	@Test
	void bindsWebhookSecretOfAtLeast16Characters() {
		contextRunner.withPropertyValues("payment.provider.http.webhook-secret=" + "s".repeat(16))
			.run((context) -> assertThat(context.getBean(HttpPaymentProviderProperties.class).webhookSecret())
				.isEqualTo("s".repeat(16)));
	}

	@Test
	void rejectsShortWebhookSecret() {
		contextRunner.withPropertyValues("payment.provider.http.webhook-secret=" + "s".repeat(15))
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.hasStackTraceContaining("webhook-secret must be empty or at least 16 characters"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "connect-timeout=0s", "read-timeout=0s", "read-timeout=-1s" })
	void rejectsTimeoutsThatAreNotPositive(String timeout) {
		contextRunner.withPropertyValues("payment.provider.http." + timeout)
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.hasStackTraceContaining("connect-timeout and read-timeout must be positive"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(HttpPaymentProviderProperties.class)
	@Import(HttpPaymentProvider.class)
	static class Config {

	}

}
