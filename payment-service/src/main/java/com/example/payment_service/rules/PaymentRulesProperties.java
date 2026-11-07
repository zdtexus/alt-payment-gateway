package com.example.payment_service.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.payment_service.entity.PaymentMethod;

@ConfigurationProperties("payment")
public record PaymentRulesProperties(Map<PaymentMethod, PaymentMethodRule> rules) {

	private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

	private static final int MAX_DECIMALS = 2;

	public PaymentRulesProperties {
		Map<PaymentMethod, PaymentMethodRule> configured = (rules != null) ? rules : Map.of();
		List<String> problems = new ArrayList<>();
		Map<PaymentMethod, PaymentMethodRule> checked = new EnumMap<>(PaymentMethod.class);
		for (PaymentMethod method : PaymentMethod.values()) {
			PaymentMethodRule rule = configured.get(method);
			if (rule == null) {
				problems.add("payment.rules." + method + " is missing: set its currencies, min-amount and max-amount");
				continue;
			}
			int before = problems.size();
			checkCurrencies(method, rule, problems);
			checkAmount(method, "min-amount", rule.minAmount(), problems);
			checkAmount(method, "max-amount", rule.maxAmount(), problems);
			if (problems.size() == before && rule.minAmount().compareTo(rule.maxAmount()) > 0) {
				problems.add(name(method, "min-amount") + " must not be greater than " + name(method, "max-amount")
						+ ", was " + rule.minAmount().toPlainString() + " > " + rule.maxAmount().toPlainString());
			}
			if (problems.size() == before) {
				checked.put(method, new PaymentMethodRule(rule.currencies(), rule.minAmount().setScale(MAX_DECIMALS),
						rule.maxAmount().setScale(MAX_DECIMALS)));
			}
		}
		if (!problems.isEmpty()) {
			throw new IllegalArgumentException("Invalid payment rules: " + String.join("; ", problems));
		}
		rules = Map.copyOf(checked);
	}

	public PaymentMethodRule forMethod(PaymentMethod method) {
		return rules.get(method);
	}

	private static void checkCurrencies(PaymentMethod method, PaymentMethodRule rule, List<String> problems) {
		if (rule.currencies().isEmpty()) {
			problems.add(name(method, "currencies") + " must list at least one currency");
			return;
		}
		for (String code : new TreeSet<>(rule.currencies())) {
			if (!CURRENCY_CODE.matcher(code).matches() || !isIsoCurrency(code)) {
				problems.add(name(method, "currencies") + " must contain ISO 4217 codes like EUR, was '" + code + "'");
			}
		}
	}

	private static void checkAmount(PaymentMethod method, String property, BigDecimal amount, List<String> problems) {
		if (amount == null) {
			problems.add(name(method, property) + " must be set");
		}
		else if (amount.signum() <= 0) {
			problems.add(name(method, property) + " must be greater than 0, was " + amount.toPlainString());
		}
		else if (amount.stripTrailingZeros().scale() > MAX_DECIMALS) {
			problems.add(name(method, property) + " must have at most " + MAX_DECIMALS + " decimals, was "
					+ amount.toPlainString());
		}
	}

	private static boolean isIsoCurrency(String code) {
		try {
			Currency.getInstance(code);
			return true;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static String name(PaymentMethod method, String property) {
		return "payment.rules." + method + "." + property + " (PAYMENT_RULES_" + method + "_"
				+ property.toUpperCase().replace('-', '_') + ")";
	}

}
