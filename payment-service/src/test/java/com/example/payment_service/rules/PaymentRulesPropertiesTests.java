package com.example.payment_service.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.example.payment_service.entity.PaymentMethod;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRulesPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfig.class);

	@Test
	void defaultsEqualTheFormerStaticTable() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer()).run((context) -> {
			PaymentRulesProperties properties = context.getBean(PaymentRulesProperties.class);
			for (PaymentMethod method : PaymentMethod.values()) {
				assertThat(properties.forMethod(method)).as(method.name()).isEqualTo(PaymentRules.forMethod(method));
			}
			assertThat(properties.rules()).containsOnlyKeys(PaymentMethod.values());
		});
	}

	@Test
	void environmentVariablesOverrideSingleValues() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer())
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
						Map.of("PAYMENT_RULES_BONUS_MAX_AMOUNT", "100", "PAYMENT_RULES_CARD_CURRENCIES", "EUR, USD",
								"PAYMENT_RULES_KVIK_MIN_AMOUNT", "1.5"))))
			.run((context) -> {
				PaymentRulesProperties properties = context.getBean(PaymentRulesProperties.class);
				assertThat(properties.forMethod(PaymentMethod.BONUS)).isEqualTo(
						new PaymentMethodRule(Set.of("EUR"), new BigDecimal("0.01"), new BigDecimal("100.00")));
				assertThat(properties.forMethod(PaymentMethod.CARD).currencies()).containsExactlyInAnyOrder("EUR",
						"USD");
				assertThat(properties.forMethod(PaymentMethod.KVIK).minAmount()).isEqualTo(new BigDecimal("1.50"));
				assertThat(properties.forMethod(PaymentMethod.CRYPTO)).isEqualTo(PaymentRules.forMethod(PaymentMethod.CRYPTO));
			});
	}

	@Test
	void amountsAreKeptWithTwoDecimals() {
		runner.withPropertyValues(rules("BONUS", "EUR", "1", "250.500")).run((context) -> {
			PaymentMethodRule bonus = context.getBean(PaymentRulesProperties.class).forMethod(PaymentMethod.BONUS);
			assertThat(bonus.minAmount()).isEqualTo(new BigDecimal("1.00"));
			assertThat(bonus.maxAmount()).isEqualTo(new BigDecimal("250.50"));
		});
	}

	@Test
	void missingMethodStopsStartup() {
		List<String> properties = new ArrayList<>(List.of(rules("BONUS", "EUR", "0.01", "500.00")));
		properties.removeIf((property) -> property.startsWith("payment.rules.KVIK"));

		runner.withPropertyValues(properties.toArray(String[]::new))
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("Invalid payment rules: payment.rules.KVIK is missing: "
						+ "set its currencies, min-amount and max-amount"));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			eur     | 0.01  | 500.00 | payment.rules.BONUS.currencies (PAYMENT_RULES_BONUS_CURRENCIES) must contain ISO 4217 codes like EUR, was 'eur'
			EU      | 0.01  | 500.00 | payment.rules.BONUS.currencies (PAYMENT_RULES_BONUS_CURRENCIES) must contain ISO 4217 codes like EUR, was 'EU'
			UER     | 0.01  | 500.00 | payment.rules.BONUS.currencies (PAYMENT_RULES_BONUS_CURRENCIES) must contain ISO 4217 codes like EUR, was 'UER'
			EUR     | 600   | 500.00 | payment.rules.BONUS.min-amount (PAYMENT_RULES_BONUS_MIN_AMOUNT) must not be greater than payment.rules.BONUS.max-amount (PAYMENT_RULES_BONUS_MAX_AMOUNT), was 600 > 500.00
			EUR     | 0.001 | 500.00 | payment.rules.BONUS.min-amount (PAYMENT_RULES_BONUS_MIN_AMOUNT) must have at most 2 decimals, was 0.001
			EUR     | -1    | 500.00 | payment.rules.BONUS.min-amount (PAYMENT_RULES_BONUS_MIN_AMOUNT) must be greater than 0, was -1
			EUR     | 0     | 500.00 | payment.rules.BONUS.min-amount (PAYMENT_RULES_BONUS_MIN_AMOUNT) must be greater than 0, was 0
			EUR     | 0.01  | 0.00   | payment.rules.BONUS.max-amount (PAYMENT_RULES_BONUS_MAX_AMOUNT) must be greater than 0, was 0.00
			""")
	void invalidRuleStopsStartupAndNamesTheSetting(String currencies, String min, String max, String problem) {
		runner.withPropertyValues(rules("BONUS", currencies, min, max))
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("Invalid payment rules: " + problem));
	}

	@Test
	void emptyCurrenciesAndMissingAmountAreReported() {
		List<String> properties = new ArrayList<>(List.of(rules("BONUS", "EUR", "0.01", "500.00")));
		properties.removeIf((property) -> property.startsWith("payment.rules.BONUS"));
		properties.add("payment.rules.BONUS.currencies=");
		properties.add("payment.rules.BONUS.max-amount=500.00");

		runner.withPropertyValues(properties.toArray(String[]::new))
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("Invalid payment rules: payment.rules.BONUS.currencies (PAYMENT_RULES_BONUS_CURRENCIES) "
						+ "must list at least one currency; payment.rules.BONUS.min-amount "
						+ "(PAYMENT_RULES_BONUS_MIN_AMOUNT) must be set"));
	}

	@Test
	void allProblemsAreReportedInOneMessage() {
		List<String> properties = new ArrayList<>(List.of(rules("BONUS", "eur", "0.001", "500.00")));
		properties.removeIf((property) -> property.startsWith("payment.rules.CRYPTO.max-amount"));
		properties.add("payment.rules.CRYPTO.max-amount=-5");

		runner.withPropertyValues(properties.toArray(String[]::new))
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("Invalid payment rules: payment.rules.BONUS.currencies (PAYMENT_RULES_BONUS_CURRENCIES) "
						+ "must contain ISO 4217 codes like EUR, was 'eur'; payment.rules.BONUS.min-amount "
						+ "(PAYMENT_RULES_BONUS_MIN_AMOUNT) must have at most 2 decimals, was 0.001; "
						+ "payment.rules.CRYPTO.max-amount (PAYMENT_RULES_CRYPTO_MAX_AMOUNT) must be greater than 0, "
						+ "was -5"));
	}

	private static String[] rules(String method, String currencies, String min, String max) {
		List<String> properties = new ArrayList<>();
		for (PaymentMethod each : PaymentMethod.values()) {
			if (each.name().equals(method)) {
				properties.add("payment.rules." + each + ".currencies=" + currencies);
				properties.add("payment.rules." + each + ".min-amount=" + min);
				properties.add("payment.rules." + each + ".max-amount=" + max);
			}
			else {
				PaymentMethodRule rule = PaymentRules.forMethod(each);
				properties.add("payment.rules." + each + ".currencies=" + String.join(",", rule.currencies()));
				properties.add("payment.rules." + each + ".min-amount=" + rule.minAmount());
				properties.add("payment.rules." + each + ".max-amount=" + rule.maxAmount());
			}
		}
		return properties.toArray(String[]::new);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(PaymentRulesProperties.class)
	static class PropertiesConfig {

	}

}
