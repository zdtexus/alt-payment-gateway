package com.example.payment_service.provider;

import java.math.BigDecimal;
import java.util.UUID;

public record CaptureRequest(UUID paymentId, String authorizationReference, String currency, BigDecimal amount) {

	public CaptureRequest {
		if (paymentId == null || currency == null) {
			throw new IllegalArgumentException("paymentId and currency are required");
		}
		if (amount == null || amount.signum() <= 0) {
			throw new IllegalArgumentException("Capture amount must be greater than 0, was " + amount);
		}
	}

}
