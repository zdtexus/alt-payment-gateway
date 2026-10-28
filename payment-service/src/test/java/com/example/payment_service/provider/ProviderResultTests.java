package com.example.payment_service.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProviderResultTests {

	@Test
	void approvedHasNoReasonAndOptionalReference() {
		assertThat(ProviderResult.approved()).isEqualTo(new ProviderResult.Approved(null));
		assertThat(ProviderResult.approved("ref-1")).isEqualTo(new ProviderResult.Approved("ref-1"));
	}

	@Test
	void declinedCarriesReasonAndOptionalReference() {
		assertThat(ProviderResult.declined("Insufficient funds"))
			.isEqualTo(new ProviderResult.Declined("Insufficient funds", null));
		assertThat(ProviderResult.declined("Insufficient funds", "ref-2"))
			.isEqualTo(new ProviderResult.Declined("Insufficient funds", "ref-2"));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "  " })
	void declinedRequiresReason(String reason) {
		assertThatIllegalArgumentException().isThrownBy(() -> ProviderResult.declined(reason))
			.withMessage("Decline reason must not be blank");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "  " })
	void referenceMustNotBeBlank(String reference) {
		assertThatIllegalArgumentException().isThrownBy(() -> ProviderResult.approved(reference))
			.withMessage("Provider reference must be null or 1 to 255 characters and not blank");
		assertThatIllegalArgumentException().isThrownBy(() -> ProviderResult.declined("Card expired", reference))
			.withMessage("Provider reference must be null or 1 to 255 characters and not blank");
	}

	@Test
	void referenceFitsTheDatabaseColumn() {
		assertThat(ProviderResult.approved("r".repeat(255))).isNotNull();
		assertThatIllegalArgumentException().isThrownBy(() -> ProviderResult.approved("r".repeat(256)));
	}

	@Test
	void resultsCanBeHandledExhaustively() {
		assertThat(describe(ProviderResult.approved("ref-1"))).isEqualTo("approved ref-1");
		assertThat(describe(ProviderResult.declined("Card expired"))).isEqualTo("declined: Card expired null");
	}

	private static String describe(ProviderResult result) {
		return switch (result) {
			case ProviderResult.Approved(String reference) -> "approved " + reference;
			case ProviderResult.Declined(String reason, String reference) -> "declined: " + reason + " " + reference;
		};
	}

}
