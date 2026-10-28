package com.example.payment_service.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.example.payment_service.provider.FixedPaymentProviderProperties.Outcome;

import static org.assertj.core.api.Assertions.assertThat;

class FixedPaymentProviderPropertiesTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(Config.class);

	@Test
	void approvesByDefault() {
		contextRunner.run((context) -> {
			FixedPaymentProviderProperties properties = context.getBean(FixedPaymentProviderProperties.class);
			assertThat(properties.outcome()).isEqualTo(Outcome.APPROVE);
			assertThat(properties.declineReason()).isEqualTo("Declined by fixed provider");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "decline", "DECLINE" })
	void bindsConfiguredValuesIgnoringCase(String outcome) {
		contextRunner
			.withPropertyValues("payment.provider.fixed.outcome=" + outcome,
					"payment.provider.fixed.decline-reason=Crypto payments are paused")
			.run((context) -> {
				FixedPaymentProviderProperties properties = context.getBean(FixedPaymentProviderProperties.class);
				assertThat(properties.outcome()).isEqualTo(Outcome.DECLINE);
				assertThat(properties.declineReason()).isEqualTo("Crypto payments are paused");
			});
	}

	@Test
	void rejectsUnknownOutcome() {
		contextRunner.withPropertyValues("payment.provider.fixed.outcome=maybe")
			.run((context) -> assertThat(context).hasFailed());
	}

	@Test
	void rejectsBlankDeclineReason() {
		contextRunner.withPropertyValues("payment.provider.fixed.decline-reason= ")
			.run((context) -> assertThat(context).hasFailed());
	}

	@Test
	void declineReasonFitsTheDatabaseColumn() {
		contextRunner.withPropertyValues("payment.provider.fixed.decline-reason=" + "r".repeat(255))
			.run((context) -> assertThat(context).hasNotFailed());
		contextRunner.withPropertyValues("payment.provider.fixed.decline-reason=" + "r".repeat(256))
			.run((context) -> assertThat(context).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(FixedPaymentProviderProperties.class)
	static class Config {

	}

}
