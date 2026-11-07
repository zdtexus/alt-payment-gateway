package com.example.payment_service.provider;

import java.util.UUID;

public record ProviderStatusRequest(UUID paymentId, String providerReference) {

	public ProviderStatusRequest {
		if (paymentId == null) {
			throw new IllegalArgumentException("paymentId is required");
		}
	}

}
