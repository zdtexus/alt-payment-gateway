package com.example.payment_service.provider;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RefundRequestTests {

	private static final UUID REFUND_ID = UUID.randomUUID();

	private static final UUID PAYMENT_ID = UUID.randomUUID();

	@Test
	void thePaymentReferenceIsOptional() {
		RefundRequest request = new RefundRequest(REFUND_ID, PAYMENT_ID, null, "EUR", new BigDecimal("0.01"));

		assertThat(request.paymentReference()).isNull();
		assertThat(request.amount()).isEqualByComparingTo("0.01");
	}

	@Test
	void idsAndCurrencyAreRequired() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new RefundRequest(null, PAYMENT_ID, "ref", "EUR", BigDecimal.ONE))
			.withMessage("refundId, paymentId and currency are required");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new RefundRequest(REFUND_ID, null, "ref", "EUR", BigDecimal.ONE));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new RefundRequest(REFUND_ID, PAYMENT_ID, "ref", null, BigDecimal.ONE));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "0", "0.00", "-1.00" })
	void amountMustBePositive(BigDecimal amount) {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new RefundRequest(REFUND_ID, PAYMENT_ID, "ref", "EUR", amount))
			.withMessageStartingWith("Refund amount must be greater than 0");
	}

}
