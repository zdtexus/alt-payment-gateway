package com.example.payment_service.rules;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.error.PaymentRuleViolationException;

@Component
public class PaymentRuleValidator {

	public void validate(PaymentMethod method, String currency, BigDecimal amount) {
		PaymentMethodRule rule = PaymentRules.forMethod(method);
		if (!rule.currencies().contains(currency)) {
			throw new PaymentRuleViolationException("Currency " + currency + " is not supported for " + method);
		}
		if (amount.compareTo(rule.minAmount()) < 0) {
			throw new PaymentRuleViolationException(
					"Amount " + format(amount) + " is below " + method + " minimum " + format(rule.minAmount()));
		}
		if (amount.compareTo(rule.maxAmount()) > 0) {
			throw new PaymentRuleViolationException(
					"Amount " + format(amount) + " exceeds " + method + " limit " + format(rule.maxAmount()));
		}
	}

	private static String format(BigDecimal amount) {
		return (amount.scale() < 2) ? amount.setScale(2).toPlainString() : amount.toPlainString();
	}

}
