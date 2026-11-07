package com.example.payment_service.rules;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.error.PaymentRuleViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class PaymentRuleValidatorTests {

	private final PaymentRuleValidator validator = new PaymentRuleValidator(TestPaymentRules.defaults());

	@Test
	void usesTheRulesItIsGiven() {
		Map<PaymentMethod, PaymentMethodRule> rules = new EnumMap<>(TestPaymentRules.defaults().rules());
		rules.put(PaymentMethod.BONUS, TestPaymentRules.rule("5.00", "100.00", "EUR", "USD"));
		PaymentRuleValidator custom = new PaymentRuleValidator(new PaymentRulesProperties(rules));

		assertThatCode(() -> custom.validate(PaymentMethod.BONUS, "USD", new BigDecimal("100.00")))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> custom.validate(PaymentMethod.BONUS, "EUR", new BigDecimal("150.00")))
			.isInstanceOf(PaymentRuleViolationException.class)
			.hasMessage("Amount 150.00 exceeds BONUS limit 100.00");
		assertThatThrownBy(() -> custom.validate(PaymentMethod.BONUS, "EUR", new BigDecimal("4.99")))
			.isInstanceOf(PaymentRuleViolationException.class)
			.hasMessage("Amount 4.99 is below BONUS minimum 5.00");
	}

	@Test
	void logsTheActiveRulesOncePerValidator(CapturedOutput output) {
		String line = "Payment rules: CARD=EUR/PLN/UAH/USD 0.50-10000.00, BONUS=EUR 0.01-500.00, "
				+ "CRYPTO=EUR/USD 1.00-5000.00, KVIK=EUR/PLN 0.50-2000.00";
		int before = occurrences(output.getAll(), line);

		new PaymentRuleValidator(TestPaymentRules.defaults());

		assertThat(occurrences(output.getAll(), line)).isEqualTo(before + 1);
	}

	private static int occurrences(String text, String line) {
		return text.split(java.util.regex.Pattern.quote(line), -1).length - 1;
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			CARD   | EUR | 0.50
			CARD   | USD | 10000.00
			CARD   | PLN | 100
			CARD   | UAH | 10000
			BONUS  | EUR | 0.01
			BONUS  | EUR | 500.00
			CRYPTO | EUR | 1.00
			CRYPTO | USD | 5000.00
			KVIK   | EUR | 0.50
			KVIK   | PLN | 2000.00
			""")
	void acceptsAmountsWithinLimits(PaymentMethod method, String currency, BigDecimal amount) {
		assertThatCode(() -> validator.validate(method, currency, amount)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			CARD   | EUR | 0.49     | Amount 0.49 is below CARD minimum 0.50
			CARD   | EUR | 10000.01 | Amount 10000.01 exceeds CARD limit 10000.00
			BONUS  | EUR | 0.00     | Amount 0.00 is below BONUS minimum 0.01
			BONUS  | EUR | 500.01   | Amount 500.01 exceeds BONUS limit 500.00
			BONUS  | EUR | 600      | Amount 600.00 exceeds BONUS limit 500.00
			CRYPTO | USD | 0.99     | Amount 0.99 is below CRYPTO minimum 1.00
			CRYPTO | USD | 5000.01  | Amount 5000.01 exceeds CRYPTO limit 5000.00
			KVIK   | PLN | 0.49     | Amount 0.49 is below KVIK minimum 0.50
			KVIK   | PLN | 2000.01  | Amount 2000.01 exceeds KVIK limit 2000.00
			CARD   | EUR | 0.499    | Amount 0.499 is below CARD minimum 0.50
			""")
	void rejectsAmountsOutsideLimits(PaymentMethod method, String currency, BigDecimal amount, String message) {
		assertThatThrownBy(() -> validator.validate(method, currency, amount))
			.isInstanceOf(PaymentRuleViolationException.class)
			.hasMessage(message);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			CARD   | GBP | Currency GBP is not supported for CARD
			BONUS  | USD | Currency USD is not supported for BONUS
			CRYPTO | PLN | Currency PLN is not supported for CRYPTO
			KVIK   | UAH | Currency UAH is not supported for KVIK
			""")
	void rejectsUnsupportedCurrencies(PaymentMethod method, String currency, String message) {
		assertThatThrownBy(() -> validator.validate(method, currency, new BigDecimal("10.00")))
			.isInstanceOf(PaymentRuleViolationException.class)
			.hasMessage(message);
	}

}
