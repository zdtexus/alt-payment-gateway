package com.example.payment_service.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProviderResultTests {

	@Test
	void approvedHasNoReason() {
		assertThat(ProviderResult.approved()).isInstanceOf(ProviderResult.Approved.class);
	}

	@Test
	void declinedCarriesReason() {
		ProviderResult result = ProviderResult.declined("Insufficient funds");

		assertThat(result).isEqualTo(new ProviderResult.Declined("Insufficient funds"));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "  " })
	void declinedRequiresReason(String reason) {
		assertThatIllegalArgumentException().isThrownBy(() -> ProviderResult.declined(reason))
			.withMessage("Decline reason must not be blank");
	}

	@Test
	void resultsCanBeHandledExhaustively() {
		assertThat(describe(ProviderResult.approved())).isEqualTo("approved");
		assertThat(describe(ProviderResult.declined("Card expired"))).isEqualTo("declined: Card expired");
	}

	private static String describe(ProviderResult result) {
		return switch (result) {
			case ProviderResult.Approved approved -> "approved";
			case ProviderResult.Declined(String reason) -> "declined: " + reason;
		};
	}

}
