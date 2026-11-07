package com.example.payment_service.rules;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import com.example.payment_service.entity.PaymentMethod;

public final class TestPaymentRules {

	private TestPaymentRules() {
	}

	public static PaymentRulesProperties defaults() {
		return new PaymentRulesProperties(Map.of(PaymentMethod.CARD, rule("0.50", "10000.00", "EUR", "USD", "PLN", "UAH"),
				PaymentMethod.BONUS, rule("0.01", "500.00", "EUR"), PaymentMethod.CRYPTO,
				rule("1.00", "5000.00", "EUR", "USD"), PaymentMethod.KVIK, rule("0.50", "2000.00", "EUR", "PLN")));
	}

	public static PaymentMethodRule rule(String min, String max, String... currencies) {
		return new PaymentMethodRule(Set.of(currencies), new BigDecimal(min), new BigDecimal(max));
	}

}
