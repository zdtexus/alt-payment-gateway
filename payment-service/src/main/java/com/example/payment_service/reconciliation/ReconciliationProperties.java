package com.example.payment_service.reconciliation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payment.reconciliation")
public record ReconciliationProperties(@DefaultValue("2m") Duration minAge) {

	public ReconciliationProperties {
		if (minAge == null || minAge.isNegative() || minAge.isZero()) {
			throw new IllegalArgumentException(
					"payment.reconciliation.min-age (PAYMENT_RECONCILIATION_MIN_AGE) must be greater than 0, was "
							+ minAge);
		}
	}

}
