package com.example.payment_service.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPropertiesTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(Config.class);

	@Test
	void bindsConfiguredKey() {
		contextRunner.withPropertyValues("admin.api-key=0123456789abcdef").run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(AdminProperties.class).apiKey()).isEqualTo("0123456789abcdef");
		});
	}

	@Test
	void refusesToStartWithoutKey() {
		contextRunner.run((context) -> assertThat(context).hasFailed()
			.getFailure()
			.rootCause()
			.hasMessageContaining("admin.api-key (ADMIN_API_KEY) is required"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void refusesToStartWithBlankKey(String key) {
		contextRunner.withPropertyValues("admin.api-key=" + key)
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("admin.api-key (ADMIN_API_KEY) is required"));
	}

	@Test
	void refusesToStartWithShortKey() {
		contextRunner.withPropertyValues("admin.api-key=0123456789abcde")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("must be at least 16 characters"));
	}

	@Test
	void toStringDoesNotRevealKey() {
		assertThat(new AdminProperties("0123456789abcdef")).hasToString("AdminProperties[apiKey=***]");
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AdminProperties.class)
	static class Config {

	}

}
