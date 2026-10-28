package com.example.payment_service.provider;

public sealed interface ProviderResult {

	int MAX_REFERENCE_LENGTH = 255;

	String reference();

	static ProviderResult approved() {
		return new Approved(null);
	}

	static ProviderResult approved(String reference) {
		return new Approved(reference);
	}

	static ProviderResult declined(String reason) {
		return new Declined(reason, null);
	}

	static ProviderResult declined(String reason, String reference) {
		return new Declined(reason, reference);
	}

	static ProviderResult pending(String reference) {
		return new Pending(reference);
	}

	private static void checkReference(String reference) {
		if (reference != null && (reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH)) {
			throw new IllegalArgumentException("Provider reference must be null or 1 to " + MAX_REFERENCE_LENGTH
					+ " characters and not blank");
		}
	}

	record Approved(String reference) implements ProviderResult {

		public Approved {
			checkReference(reference);
		}

	}

	record Declined(String reason, String reference) implements ProviderResult {

		public Declined {
			if (reason == null || reason.isBlank()) {
				throw new IllegalArgumentException("Decline reason must not be blank");
			}
			checkReference(reference);
		}

	}

	record Pending(String reference) implements ProviderResult {

		public Pending {
			if (reference == null) {
				throw new IllegalArgumentException("Pending result needs a provider reference");
			}
			checkReference(reference);
		}

	}

}
