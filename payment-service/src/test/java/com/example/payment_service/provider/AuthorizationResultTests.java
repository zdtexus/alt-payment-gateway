package com.example.payment_service.provider;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AuthorizationResultTests {

	private static final Instant EXPIRES = Instant.parse("2026-01-23T09:00:00Z");

	@Test
	void anApprovalCarriesItsExpiry() {
		AuthorizationResult result = AuthorizationResult.approved("auth_1", EXPIRES);

		assertThat(result.result()).isEqualTo(ProviderResult.approved("auth_1"));
		assertThat(result.expiresAt()).isEqualTo(EXPIRES);
	}

	@Test
	void anApprovalNeedsAnExpiry() {
		assertThatIllegalArgumentException().isThrownBy(() -> AuthorizationResult.approved("auth_1", null))
			.withMessage("An approved authorization needs an expiry");
	}

	@Test
	void aDeclineHasNoExpiry() {
		assertThat(AuthorizationResult.declined("Insufficient funds", "auth_1").expiresAt()).isNull();
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new AuthorizationResult(ProviderResult.declined("Insufficient funds"), EXPIRES))
			.withMessage("A declined authorization has no expiry");
	}

	@Test
	void aPendingAuthorizationMayAlreadyKnowItsExpiry() {
		assertThat(AuthorizationResult.pending("auth_1").expiresAt()).isNull();
		assertThatCode(() -> new AuthorizationResult(ProviderResult.pending("auth_1"), EXPIRES))
			.doesNotThrowAnyException();
	}

	@Test
	void theResultIsRequired() {
		assertThatIllegalArgumentException().isThrownBy(() -> new AuthorizationResult(null, EXPIRES))
			.withMessage("Authorization result is required");
	}

}
