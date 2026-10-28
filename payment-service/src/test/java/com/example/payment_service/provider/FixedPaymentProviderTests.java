package com.example.payment_service.provider;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.provider.FixedPaymentProviderProperties.Outcome;

import static org.assertj.core.api.Assertions.assertThat;

class FixedPaymentProviderTests {

	private static final ProviderRequest REQUEST = new ProviderRequest(UUID.randomUUID(), PaymentMethod.CRYPTO, "EUR",
			new BigDecimal("10.00"));

	@Test
	void approveOutcomeAlwaysApproves() {
		FixedPaymentProvider provider = provider(Outcome.APPROVE, "unused");

		for (int i = 0; i < 20; i++) {
			assertThat(provider.process(REQUEST)).isInstanceOf(ProviderResult.Approved.class);
		}
	}

	@Test
	void declineOutcomeAlwaysDeclinesWithConfiguredReason() {
		FixedPaymentProvider provider = provider(Outcome.DECLINE, "Crypto payments are paused");

		for (int i = 0; i < 20; i++) {
			assertThat(provider.process(REQUEST)).isInstanceOfSatisfying(ProviderResult.Declined.class,
					(declined) -> assertThat(declined.reason()).isEqualTo("Crypto payments are paused"));
		}
	}

	@Test
	void everyResultHasItsOwnFixedReference() {
		Set<String> references = new HashSet<>();
		for (Outcome outcome : Outcome.values()) {
			FixedPaymentProvider provider = provider(outcome, "Declined");
			for (int i = 0; i < 50; i++) {
				references.add(switch (provider.process(REQUEST)) {
					case ProviderResult.Approved(String reference) -> reference;
					case ProviderResult.Declined(String reason, String reference) -> reference;
					case ProviderResult.Pending(String reference) -> reference;
				});
			}
		}

		assertThat(references).hasSize(100).allMatch((reference) -> reference.matches("fix_[0-9a-f-]{36}"));
	}

	@Test
	void isNamedFixed() {
		assertThat(provider(Outcome.APPROVE, "unused").name()).isEqualTo("fixed");
	}

	private static FixedPaymentProvider provider(Outcome outcome, String declineReason) {
		return new FixedPaymentProvider(new FixedPaymentProviderProperties(outcome, declineReason));
	}

}
