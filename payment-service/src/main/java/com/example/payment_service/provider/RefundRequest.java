package com.example.payment_service.provider;

import java.math.BigDecimal;
import java.util.UUID;

public record RefundRequest(UUID refundId, UUID paymentId, String paymentReference, String currency,
		BigDecimal amount) {

	public RefundRequest {
		if (refundId == null || paymentId == null || currency == null) {
			throw new IllegalArgumentException("refundId, paymentId and currency are required");
		}
		if (amount == null || amount.signum() <= 0) {
			throw new IllegalArgumentException("Refund amount must be greater than 0, was " + amount);
		}
	}

}
