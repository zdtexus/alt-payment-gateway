package com.example.payment_service.notification.sms;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class SmsSenderSelectionTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(Config.class);

	@Test
	void usesLoggingSenderByDefault() {
		contextRunner.run((context) -> {
			assertThat(context).hasSingleBean(SmsSender.class).hasSingleBean(LoggingSmsSender.class);
			assertThat(context.getBean(SmsProperties.class).provider()).isEqualTo(SmsProperties.Provider.LOG);
			assertThat(context.getBean(SmsProperties.class).http().timeout()).isEqualTo(Duration.ofSeconds(5));
		});
	}

	@Test
	void usesLoggingSenderWhenProviderIsLog() {
		contextRunner.withPropertyValues("notification.sms.provider=log")
			.run((context) -> assertThat(context).hasSingleBean(SmsSender.class)
				.hasSingleBean(LoggingSmsSender.class));
	}

	@Test
	void usesHttpSenderWhenProviderIsHttp() {
		contextRunner
			.withPropertyValues("notification.sms.provider=http", "notification.sms.http.url=http://sms.local/send",
					"notification.sms.http.api-key=secret", "notification.sms.http.timeout=2s")
			.run((context) -> {
				assertThat(context).hasSingleBean(SmsSender.class).hasSingleBean(HttpSmsSender.class);
				SmsProperties.Http http = context.getBean(SmsProperties.class).http();
				assertThat(http.url()).isEqualTo(URI.create("http://sms.local/send"));
				assertThat(http.apiKey()).isEqualTo("secret");
				assertThat(http.timeout()).isEqualTo(Duration.ofSeconds(2));
			});
	}

	@Test
	void httpProviderWithoutUrlFailsAtStartup() {
		contextRunner.withPropertyValues("notification.sms.provider=http")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("notification.sms.http.url is required when the provider is http"));
	}

	@Test
	void unknownProviderFailsAtStartup() {
		contextRunner.withPropertyValues("notification.sms.provider=pigeon")
			.run((context) -> assertThat(context).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(SmsProperties.class)
	@Import({ LoggingSmsSender.class, HttpSmsSender.class })
	static class Config {

	}

}
