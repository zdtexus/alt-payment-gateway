package com.example.payment_service.rules;

import java.math.BigDecimal;
import java.util.Set;

import com.example.payment_service.entity.PaymentMethod;

public final class PaymentRules {

	private static final PaymentMethodRule CARD = new PaymentMethodRule(Set.of("EUR", "USD", "PLN", "UAH"),
			new BigDecimal("0.50"), new BigDecimal("10000.00"));

	private static final PaymentMethodRule BONUS = new PaymentMethodRule(Set.of("EUR"), new BigDecimal("0.01"),
			new BigDecimal("500.00"));

	private static final PaymentMethodRule CRYPTO = new PaymentMethodRule(Set.of("EUR", "USD"),
			new BigDecimal("1.00"), new BigDecimal("5000.00"));

	private static final PaymentMethodRule KVIK = new PaymentMethodRule(Set.of("EUR", "PLN"), new BigDecimal("0.50"),
			new BigDecimal("2000.00"));

	private PaymentRules() {
	}

	public static PaymentMethodRule forMethod(PaymentMethod method) {
		return switch (method) {
			case CARD -> CARD;
			case BONUS -> BONUS;
			case CRYPTO -> CRYPTO;
			case KVIK -> KVIK;
		};
	}

}
