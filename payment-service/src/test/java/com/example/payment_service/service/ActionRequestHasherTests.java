package com.example.payment_service.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.payment_service.entity.PendingAction;

import static org.assertj.core.api.Assertions.assertThat;

class ActionRequestHasherTests {

	@Test
	void isAStableSha256Hex() {
		assertThat(ActionRequestHasher.hash(PendingAction.CAPTURE, new BigDecimal("30.00"))).matches("[0-9a-f]{64}")
			.isEqualTo(ActionRequestHasher.hash(PendingAction.CAPTURE, new BigDecimal("30.00")));
	}

	@Test
	void sameAmountWrittenDifferentlyGivesTheSameHash() {
		assertThat(ActionRequestHasher.hash(PendingAction.CAPTURE, new BigDecimal("30")))
			.isEqualTo(ActionRequestHasher.hash(PendingAction.CAPTURE, new BigDecimal("30.00")));
	}

	@Test
	void actionAndAmountBothCount() {
		String capture = ActionRequestHasher.hash(PendingAction.CAPTURE, null);

		assertThat(ActionRequestHasher.hash(PendingAction.VOID, null)).isNotEqualTo(capture);
		assertThat(ActionRequestHasher.hash(PendingAction.CAPTURE, new BigDecimal("49.90"))).isNotEqualTo(capture);
		assertThat(ActionRequestHasher.hash(PendingAction.CAPTURE, new BigDecimal("30.01")))
			.isNotEqualTo(ActionRequestHasher.hash(PendingAction.CAPTURE, new BigDecimal("30.00")));
	}

}
