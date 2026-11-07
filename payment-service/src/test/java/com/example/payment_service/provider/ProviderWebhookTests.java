package com.example.payment_service.provider;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProviderWebhookTests {

	private static final UUID PAYMENT_ID = UUID.randomUUID();

	private static final Instant EXPIRES = Instant.parse("2026-01-22T10:30:00Z");

	@Test
	void theShortConstructorsKeepTheirTypes() {
		assertThat(new ProviderWebhook(PAYMENT_ID, ProviderResult.approved("r")).type())
			.isEqualTo(ProviderWebhook.Type.PAYMENT);
		assertThat(ProviderWebhook.refund(UUID.randomUUID(), ProviderResult.approved("r")).type())
			.isEqualTo(ProviderWebhook.Type.REFUND);
		assertThat(ProviderWebhook.capture(PAYMENT_ID, ProviderResult.approved("r")).type())
			.isEqualTo(ProviderWebhook.Type.CAPTURE);
		assertThat(ProviderWebhook.voided(PAYMENT_ID, ProviderResult.approved("r")).type())
			.isEqualTo(ProviderWebhook.Type.VOID);
	}

	@Test
	void anAuthorizationWebhookCarriesItsResultAndExpiry() {
		ProviderWebhook approved = ProviderWebhook.authorization(null, ProviderResult.approved("a"), EXPIRES);
		ProviderWebhook declined = ProviderWebhook.authorization(null, ProviderResult.declined("No", "a"), EXPIRES);

		assertThat(approved.authorization()).isEqualTo(AuthorizationResult.approved("a", EXPIRES));
		assertThat(declined.authorization()).isEqualTo(AuthorizationResult.declined("No", "a"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ProviderWebhook.authorization(PAYMENT_ID, ProviderResult.approved("a"), null))
			.withMessage("An approved authorization webhook needs expiresAt");
	}

	@Test
	void captureAndVoidWebhooksNeedThePayment() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ProviderWebhook.capture(null, ProviderResult.approved("c")))
			.withMessage("A capture webhook needs a paymentId");
		assertThatIllegalArgumentException().isThrownBy(() -> ProviderWebhook.voided(null, ProviderResult.approved("v")))
			.withMessage("A void webhook needs a paymentId");
	}

}
