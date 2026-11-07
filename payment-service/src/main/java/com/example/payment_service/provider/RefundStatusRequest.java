package com.example.payment_service.provider;

import java.util.UUID;

public record RefundStatusRequest(UUID refundId, UUID paymentId, String providerReference) {

	public RefundStatusRequest {
		if (refundId == null || paymentId == null) {
			throw new IllegalArgumentException("refundId and paymentId are required");
		}
	}

}
