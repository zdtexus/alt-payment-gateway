package com.example.payment_service.provider;

public sealed interface ProviderResult {

	static ProviderResult approved() {
		return new Approved();
	}

	static ProviderResult declined(String reason) {
		return new Declined(reason);
	}

	record Approved() implements ProviderResult {
	}

	record Declined(String reason) implements ProviderResult {

		public Declined {
			if (reason == null || reason.isBlank()) {
				throw new IllegalArgumentException("Decline reason must not be blank");
			}
		}

	}

}
