package com.example.payment_service.rules;

import java.math.BigDecimal;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.example.payment_service.entity.PaymentMethod;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRulesTests {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			CARD   | EUR,USD,PLN,UAH | 0.50 | 10000.00
			BONUS  | EUR             | 0.01 | 500.00
			CRYPTO | EUR,USD         | 1.00 | 5000.00
			KVIK   | EUR,PLN         | 0.50 | 2000.00
			""")
	void matchesReadmeTable(PaymentMethod method, String currencies, BigDecimal min, BigDecimal max) {
		PaymentMethodRule rule = PaymentRules.forMethod(method);

		assertThat(rule.currencies()).isEqualTo(Set.of(currencies.split(",")));
		assertThat(rule.minAmount()).isEqualByComparingTo(min);
		assertThat(rule.maxAmount()).isEqualByComparingTo(max);
	}

}
