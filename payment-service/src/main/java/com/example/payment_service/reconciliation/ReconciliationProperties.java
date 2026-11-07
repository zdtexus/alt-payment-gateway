package com.example.payment_service.reconciliation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payment.reconciliation")
public record ReconciliationProperties(@DefaultValue("true") boolean enabled, @DefaultValue("1m") Duration interval,
		@DefaultValue("2m") Duration minAge, @DefaultValue("50") int batchSize,
		@DefaultValue("1h") Duration maxBackoff, @DefaultValue("24h") Duration alertAfter) {

	static final int MAX_BATCH_SIZE = 1000;

	public ReconciliationProperties {
		List<String> problems = new ArrayList<>();
		requirePositive("interval", interval, problems);
		requirePositive("min-age", minAge, problems);
		requirePositive("max-backoff", maxBackoff, problems);
		requirePositive("alert-after", alertAfter, problems);
		if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
			problems.add(name("batch-size") + " must be between 1 and " + MAX_BATCH_SIZE + ", was " + batchSize);
		}
		if (problems.isEmpty() && maxBackoff.compareTo(interval) < 0) {
			problems.add(name("max-backoff") + " must be at least " + name("interval") + ", was " + maxBackoff
					+ " < " + interval);
		}
		if (!problems.isEmpty()) {
			throw new IllegalArgumentException("Invalid reconciliation settings: " + String.join("; ", problems));
		}
	}

	private static void requirePositive(String property, Duration value, List<String> problems) {
		if (value == null || value.isNegative() || value.isZero()) {
			problems.add(name(property) + " must be greater than 0, was " + value);
		}
	}

	private static String name(String property) {
		return "payment.reconciliation." + property + " (PAYMENT_RECONCILIATION_"
				+ property.toUpperCase().replace('-', '_') + ")";
	}

}
