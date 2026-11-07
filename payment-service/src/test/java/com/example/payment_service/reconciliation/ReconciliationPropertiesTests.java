package com.example.payment_service.reconciliation;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationPropertiesTests {

	private static final ReconciliationProperties DEFAULTS = new ReconciliationProperties(true, Duration.ofMinutes(1),
			Duration.ofMinutes(2), 50, Duration.ofHours(1), Duration.ofHours(24));

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfig.class);

	@Test
	void defaultsComeFromTheRecord() {
		runner.run((context) -> assertThat(context.getBean(ReconciliationProperties.class)).isEqualTo(DEFAULTS));
	}

	@Test
	void applicationYmlHasTheSameDefaultsAndTestsSwitchTheScheduleOff() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer())
			.run((context) -> {
				ReconciliationProperties properties = context.getBean(ReconciliationProperties.class);
				assertThat(properties).usingRecursiveComparison().ignoringFields("enabled").isEqualTo(DEFAULTS);
				assertThat(properties.enabled()).isFalse();
			});
	}

	@Test
	void everySettingHasAnEnvironmentVariable() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer())
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
						Map.of("PAYMENT_RECONCILIATION_ENABLED", "false", "PAYMENT_RECONCILIATION_INTERVAL", "10s",
								"PAYMENT_RECONCILIATION_MIN_AGE", "30s", "PAYMENT_RECONCILIATION_BATCH_SIZE", "5",
								"PAYMENT_RECONCILIATION_MAX_BACKOFF", "5m", "PAYMENT_RECONCILIATION_ALERT_AFTER",
								"2h"))))
			.run((context) -> assertThat(context.getBean(ReconciliationProperties.class))
				.isEqualTo(new ReconciliationProperties(false, Duration.ofSeconds(10), Duration.ofSeconds(30), 5,
						Duration.ofMinutes(5), Duration.ofHours(2))));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			interval    | 0s   | payment.reconciliation.interval (PAYMENT_RECONCILIATION_INTERVAL) must be greater than 0, was PT0S
			min-age     | -1m  | payment.reconciliation.min-age (PAYMENT_RECONCILIATION_MIN_AGE) must be greater than 0, was PT-1M
			max-backoff | 0s   | payment.reconciliation.max-backoff (PAYMENT_RECONCILIATION_MAX_BACKOFF) must be greater than 0, was PT0S
			alert-after | 0s   | payment.reconciliation.alert-after (PAYMENT_RECONCILIATION_ALERT_AFTER) must be greater than 0, was PT0S
			batch-size  | 0    | payment.reconciliation.batch-size (PAYMENT_RECONCILIATION_BATCH_SIZE) must be between 1 and 1000, was 0
			batch-size  | 1001 | payment.reconciliation.batch-size (PAYMENT_RECONCILIATION_BATCH_SIZE) must be between 1 and 1000, was 1001
			max-backoff | 30s  | payment.reconciliation.max-backoff (PAYMENT_RECONCILIATION_MAX_BACKOFF) must be at least payment.reconciliation.interval (PAYMENT_RECONCILIATION_INTERVAL), was PT30S < PT1M
			""")
	void invalidSettingStopsStartupAndNamesIt(String property, String value, String problem) {
		runner.withPropertyValues("payment.reconciliation." + property + "=" + value)
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("Invalid reconciliation settings: " + problem));
	}

	@Test
	void allProblemsAreReportedInOneMessage() {
		runner.withPropertyValues("payment.reconciliation.interval=0s", "payment.reconciliation.batch-size=0")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("Invalid reconciliation settings: payment.reconciliation.interval "
						+ "(PAYMENT_RECONCILIATION_INTERVAL) must be greater than 0, was PT0S; "
						+ "payment.reconciliation.batch-size (PAYMENT_RECONCILIATION_BATCH_SIZE) must be between 1 "
						+ "and 1000, was 0"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ReconciliationProperties.class)
	static class PropertiesConfig {

	}

}
