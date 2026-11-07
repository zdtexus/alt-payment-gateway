package com.example.payment_service.rules;

import java.math.BigDecimal;
import java.util.Set;

public record PaymentMethodRule(Set<String> currencies, BigDecimal minAmount, BigDecimal maxAmount) {

	public PaymentMethodRule {
		currencies = (currencies != null) ? Set.copyOf(currencies) : Set.of();
	}

}
