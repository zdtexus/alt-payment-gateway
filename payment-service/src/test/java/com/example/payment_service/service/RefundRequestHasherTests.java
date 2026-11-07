package com.example.payment_service.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundRequestHasherTests {

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	@Test
	void isAStableSha256Hex() {
		String hash = RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("10.00"), "Damaged");

		assertThat(hash).matches("[0-9a-f]{64}")
			.isEqualTo(RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("10.00"), "Damaged"));
	}

	@Test
	void sameAmountWrittenDifferentlyGivesTheSameHash() {
		assertThat(RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("10"), null))
			.isEqualTo(RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("10.00"), null));
	}

	@Test
	void paymentAmountAndReasonAllCount() {
		String base = RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("10.00"), "Damaged");

		assertThat(RefundRequestHasher.hash(UUID.randomUUID(), new BigDecimal("10.00"), "Damaged")).isNotEqualTo(base);
		assertThat(RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("10.01"), "Damaged")).isNotEqualTo(base);
		assertThat(RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("10.00"), "Late")).isNotEqualTo(base);
		assertThat(RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("10.00"), null)).isNotEqualTo(base);
	}

	@Test
	void theRemainingAmountIsNotTheSameAsAnExplicitOne() {
		assertThat(RefundRequestHasher.hash(PAYMENT_ID, null, null))
			.isNotEqualTo(RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("50.00"), null));
	}

}
