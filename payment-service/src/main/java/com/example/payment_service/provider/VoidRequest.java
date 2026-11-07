package com.example.payment_service.provider;

import java.util.UUID;

public record VoidRequest(UUID paymentId, String authorizationReference) {

	public VoidRequest {
		if (paymentId == null) {
			throw new IllegalArgumentException("paymentId is required");
		}
	}

}
