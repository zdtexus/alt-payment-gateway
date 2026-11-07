package com.example.payment_service.provider;

import java.time.Instant;

public record AuthorizationResult(ProviderResult result, Instant expiresAt) {

	public AuthorizationResult {
		if (result == null) {
			throw new IllegalArgumentException("Authorization result is required");
		}
		if (result instanceof ProviderResult.Approved && expiresAt == null) {
			throw new IllegalArgumentException("An approved authorization needs an expiry");
		}
		if (result instanceof ProviderResult.Declined && expiresAt != null) {
			throw new IllegalArgumentException("A declined authorization has no expiry");
		}
	}

	public static AuthorizationResult approved(String reference, Instant expiresAt) {
		return new AuthorizationResult(ProviderResult.approved(reference), expiresAt);
	}

	public static AuthorizationResult declined(String reason, String reference) {
		return new AuthorizationResult(ProviderResult.declined(reason, reference), null);
	}

	public static AuthorizationResult pending(String reference) {
		return new AuthorizationResult(ProviderResult.pending(reference), null);
	}

}
