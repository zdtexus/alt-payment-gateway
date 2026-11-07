package com.example.payment_service.provider;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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

	private static final RefundRequest REFUND = new RefundRequest(UUID.randomUUID(), UUID.randomUUID(), null, "EUR",
			new BigDecimal("4.00"));

	@Test
	void refundsFollowTheConfiguredOutcome() {
		assertThat(provider(Outcome.APPROVE, "unused").refund(REFUND)).isInstanceOfSatisfying(
				ProviderResult.Approved.class,
				(approved) -> assertThat(approved.reference()).matches("fix_r_[0-9a-f-]{36}"));
		assertThat(provider(Outcome.DECLINE, "Refunds are paused").refund(REFUND)).isInstanceOfSatisfying(
				ProviderResult.Declined.class, (declined) -> {
					assertThat(declined.reason()).isEqualTo("Refunds are paused");
					assertThat(declined.reference()).matches("fix_r_[0-9a-f-]{36}");
				});
	}

	@Test
	void everyRefundHasItsOwnReferenceAndRefundsAreSupported() {
		FixedPaymentProvider provider = provider(Outcome.APPROVE, "unused");

		Set<String> references = new HashSet<>();
		for (int i = 0; i < 20; i++) {
			references.add(provider.refund(REFUND).reference());
		}

		assertThat(references).hasSize(20);
		assertThat(provider.supportsRefunds()).isTrue();
	}

	private static final CaptureRequest CAPTURE = new CaptureRequest(UUID.randomUUID(), "fix_a_1", "EUR",
			new BigDecimal("6.00"));

	private static final VoidRequest VOID = new VoidRequest(UUID.randomUUID(), "fix_a_1");

	@Test
	void authorizationsFollowTheConfiguredOutcome() {
		FixedPaymentProvider approving = new FixedPaymentProvider(
				new FixedPaymentProviderProperties(Outcome.APPROVE, "unused", Duration.ofHours(12)));

		Instant before = Instant.now();
		AuthorizationResult approved = approving.authorize(REQUEST);
		Instant after = Instant.now();
		AuthorizationResult declined = provider(Outcome.DECLINE, "Cards are paused").authorize(REQUEST);

		assertThat(approved.result()).isInstanceOfSatisfying(ProviderResult.Approved.class,
				(result) -> assertThat(result.reference()).matches("fix_a_[0-9a-f-]{36}"));
		assertThat(approved.expiresAt()).isBetween(before.plus(Duration.ofHours(12)), after.plus(Duration.ofHours(12)));
		assertThat(declined.result()).isInstanceOfSatisfying(ProviderResult.Declined.class, (result) -> {
			assertThat(result.reason()).isEqualTo("Cards are paused");
			assertThat(result.reference()).matches("fix_a_[0-9a-f-]{36}");
		});
		assertThat(declined.expiresAt()).isNull();
	}

	@Test
	void capturesAndVoidsFollowTheConfiguredOutcome() {
		FixedPaymentProvider approving = provider(Outcome.APPROVE, "unused");
		FixedPaymentProvider declining = provider(Outcome.DECLINE, "Captures are paused");

		assertThat(approving.capture(CAPTURE)).isInstanceOfSatisfying(ProviderResult.Approved.class,
				(approved) -> assertThat(approved.reference()).matches("fix_c_[0-9a-f-]{36}"));
		assertThat(approving.voidAuthorization(VOID)).isInstanceOfSatisfying(ProviderResult.Approved.class,
				(approved) -> assertThat(approved.reference()).matches("fix_v_[0-9a-f-]{36}"));
		assertThat(declining.capture(CAPTURE)).isInstanceOfSatisfying(ProviderResult.Declined.class, (declined) -> {
			assertThat(declined.reason()).isEqualTo("Captures are paused");
			assertThat(declined.reference()).matches("fix_c_[0-9a-f-]{36}");
		});
		assertThat(declining.voidAuthorization(VOID)).isInstanceOfSatisfying(ProviderResult.Declined.class,
				(declined) -> assertThat(declined.reference()).matches("fix_v_[0-9a-f-]{36}"));
	}

	@Test
	void supportsManualCaptureWithItsOwnReferences() {
		FixedPaymentProvider provider = provider(Outcome.APPROVE, "unused");

		Set<String> references = new HashSet<>();
		for (int i = 0; i < 20; i++) {
			references.add(provider.authorize(REQUEST).result().reference());
			references.add(provider.capture(CAPTURE).reference());
			references.add(provider.voidAuthorization(VOID).reference());
		}

		assertThat(references).hasSize(60);
		assertThat(provider.supportsManualCapture()).isTrue();
	}

	@Test
	void isNamedFixed() {
		assertThat(provider(Outcome.APPROVE, "unused").name()).isEqualTo("fixed");
	}

	private static FixedPaymentProvider provider(Outcome outcome, String declineReason) {
		return new FixedPaymentProvider(new FixedPaymentProviderProperties(outcome, declineReason, Duration.ofDays(7)));
	}

}
