package com.example.payment_service.provider;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CaptureAndVoidRequestTests {

	private static final UUID PAYMENT_ID = UUID.randomUUID();

	@Test
	void theAuthorizationReferenceIsOptional() {
		CaptureRequest capture = new CaptureRequest(PAYMENT_ID, null, "EUR", new BigDecimal("0.01"));
		VoidRequest voidRequest = new VoidRequest(PAYMENT_ID, null);

		assertThat(capture.authorizationReference()).isNull();
		assertThat(capture.amount()).isEqualByComparingTo("0.01");
		assertThat(voidRequest.authorizationReference()).isNull();
	}

	@Test
	void aCaptureNeedsThePaymentAndCurrency() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new CaptureRequest(null, "auth_1", "EUR", BigDecimal.ONE))
			.withMessage("paymentId and currency are required");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new CaptureRequest(PAYMENT_ID, "auth_1", null, BigDecimal.ONE));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "0", "0.00", "-1.00" })
	void captureAmountMustBePositive(BigDecimal amount) {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new CaptureRequest(PAYMENT_ID, "auth_1", "EUR", amount))
			.withMessageStartingWith("Capture amount must be greater than 0");
	}

	@Test
	void aVoidNeedsThePayment() {
		assertThatIllegalArgumentException().isThrownBy(() -> new VoidRequest(null, "auth_1"))
			.withMessage("paymentId is required");
	}

}
