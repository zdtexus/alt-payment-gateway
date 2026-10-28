package com.example.payment_service.provider;

import java.util.UUID;

public record ProviderWebhook(UUID paymentId, ProviderResult result) {

	public ProviderWebhook {
		if (result == null || result.reference() == null) {
			throw new IllegalArgumentException("A webhook result needs a provider reference");
		}
	}

}
