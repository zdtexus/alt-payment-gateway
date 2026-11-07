package com.example.payment_service.provider;

import java.time.Instant;
import java.util.UUID;

public record ProviderWebhook(Type type, UUID paymentId, UUID refundId, ProviderResult result, Instant expiresAt) {

	public enum Type {

		PAYMENT, REFUND, AUTHORIZATION, CAPTURE, VOID

	}

	public ProviderWebhook {
		if (type == null) {
			throw new IllegalArgumentException("A webhook needs a type");
		}
		if (result == null || result.reference() == null) {
			throw new IllegalArgumentException("A webhook result needs a provider reference");
		}
		if ((type == Type.CAPTURE || type == Type.VOID) && paymentId == null) {
			throw new IllegalArgumentException("A " + type.name().toLowerCase() + " webhook needs a paymentId");
		}
		if (type == Type.AUTHORIZATION && result instanceof ProviderResult.Approved && expiresAt == null) {
			throw new IllegalArgumentException("An approved authorization webhook needs expiresAt");
		}
	}

	public ProviderWebhook(Type type, UUID paymentId, UUID refundId, ProviderResult result) {
		this(type, paymentId, refundId, result, null);
	}

	public ProviderWebhook(UUID paymentId, ProviderResult result) {
		this(Type.PAYMENT, paymentId, null, result, null);
	}

	public static ProviderWebhook refund(UUID refundId, ProviderResult result) {
		return new ProviderWebhook(Type.REFUND, null, refundId, result, null);
	}

	public static ProviderWebhook authorization(UUID paymentId, ProviderResult result, Instant expiresAt) {
		return new ProviderWebhook(Type.AUTHORIZATION, paymentId, null, result, expiresAt);
	}

	public static ProviderWebhook capture(UUID paymentId, ProviderResult result) {
		return new ProviderWebhook(Type.CAPTURE, paymentId, null, result, null);
	}

	public static ProviderWebhook voided(UUID paymentId, ProviderResult result) {
		return new ProviderWebhook(Type.VOID, paymentId, null, result, null);
	}

	public AuthorizationResult authorization() {
		return new AuthorizationResult(result, (result instanceof ProviderResult.Declined) ? null : expiresAt);
	}

}
