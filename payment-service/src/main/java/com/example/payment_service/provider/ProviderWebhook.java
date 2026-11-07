package com.example.payment_service.provider;

import java.util.UUID;

public record ProviderWebhook(Type type, UUID paymentId, UUID refundId, ProviderResult result) {

	public enum Type {

		PAYMENT, REFUND

	}

	public ProviderWebhook {
		if (type == null) {
			throw new IllegalArgumentException("A webhook needs a type");
		}
		if (result == null || result.reference() == null) {
			throw new IllegalArgumentException("A webhook result needs a provider reference");
		}
	}

	public ProviderWebhook(UUID paymentId, ProviderResult result) {
		this(Type.PAYMENT, paymentId, null, result);
	}

	public static ProviderWebhook refund(UUID refundId, ProviderResult result) {
		return new ProviderWebhook(Type.REFUND, null, refundId, result);
	}

}
