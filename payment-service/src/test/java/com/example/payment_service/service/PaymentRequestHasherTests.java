package com.example.payment_service.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.entity.CaptureMethod;
import com.example.payment_service.entity.PaymentMethod;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRequestHasherTests {

	@Test
	void hashIsSha256HexOfCanonicalForm() {
		assertThat(PaymentRequestHasher.hash(request("49.90", "EUR", PaymentMethod.CARD))).hasSize(64)
			.matches("[0-9a-f]{64}");
	}

	@Test
	void sameRequestGivesSameHash() {
		assertThat(PaymentRequestHasher.hash(request("49.90", "EUR", PaymentMethod.CARD)))
			.isEqualTo(PaymentRequestHasher.hash(request("49.90", "EUR", PaymentMethod.CARD)));
	}

	@Test
	void trailingZerosDoNotChangeHash() {
		String hash = PaymentRequestHasher.hash(request("50", "EUR", PaymentMethod.CARD));

		assertThat(PaymentRequestHasher.hash(request("50.0", "EUR", PaymentMethod.CARD))).isEqualTo(hash);
		assertThat(PaymentRequestHasher.hash(request("50.00", "EUR", PaymentMethod.CARD))).isEqualTo(hash);
	}

	@Test
	void everyFieldChangesHash() {
		String hash = PaymentRequestHasher.hash(request("49.90", "EUR", PaymentMethod.CARD));

		assertThat(PaymentRequestHasher.hash(request("49.91", "EUR", PaymentMethod.CARD))).isNotEqualTo(hash);
		assertThat(PaymentRequestHasher.hash(request("49.90", "USD", PaymentMethod.CARD))).isNotEqualTo(hash);
		assertThat(PaymentRequestHasher.hash(request("49.90", "EUR", PaymentMethod.KVIK))).isNotEqualTo(hash);
	}

	@Test
	void automaticRequestsKeepTheHashTheyHadBeforeCaptureMethods() throws Exception {
		String stored = HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest("49.9|EUR|CARD".getBytes(StandardCharsets.UTF_8)));

		assertThat(PaymentRequestHasher.hash(request("49.90", "EUR", PaymentMethod.CARD))).isEqualTo(stored);
		assertThat(PaymentRequestHasher.hash(
				new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD, CaptureMethod.AUTOMATIC)))
			.isEqualTo(stored);
	}

	@Test
	void manualCaptureChangesTheHash() {
		assertThat(PaymentRequestHasher.hash(
				new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD, CaptureMethod.MANUAL)))
			.isNotEqualTo(PaymentRequestHasher.hash(request("49.90", "EUR", PaymentMethod.CARD)));
	}

	private static CreatePaymentRequest request(String amount, String currency, PaymentMethod method) {
		return new CreatePaymentRequest(new BigDecimal(amount), currency, method);
	}

}
