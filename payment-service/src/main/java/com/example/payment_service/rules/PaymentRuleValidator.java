package com.example.payment_service.rules;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.error.PaymentRuleViolationException;

@Component
public class PaymentRuleValidator {

	private static final Logger log = LoggerFactory.getLogger(PaymentRuleValidator.class);

	private final PaymentRulesProperties rules;

	public PaymentRuleValidator(PaymentRulesProperties rules) {
		this.rules = rules;
		log.info("Payment rules: {}", describe(rules));
	}

	public void validate(PaymentMethod method, String currency, BigDecimal amount) {
		PaymentMethodRule rule = rules.forMethod(method);
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

	static String describe(PaymentRulesProperties rules) {
		return Arrays.stream(PaymentMethod.values()).map((method) -> {
			PaymentMethodRule rule = rules.forMethod(method);
			return method + "=" + String.join("/", new TreeSet<>(rule.currencies())) + " "
					+ rule.minAmount().toPlainString() + "-" + rule.maxAmount().toPlainString();
		}).collect(Collectors.joining(", "));
	}

	private static String format(BigDecimal amount) {
		return (amount.scale() < 2) ? amount.setScale(2).toPlainString() : amount.toPlainString();
	}

}
