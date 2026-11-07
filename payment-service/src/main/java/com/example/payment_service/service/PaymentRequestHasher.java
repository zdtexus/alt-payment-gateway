package com.example.payment_service.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.entity.CaptureMethod;

final class PaymentRequestHasher {

	private PaymentRequestHasher() {
	}

	static String hash(CreatePaymentRequest request) {
		String canonical = String.join("|", request.amount().stripTrailingZeros().toPlainString(),
				request.currency(), request.paymentMethod().name());
		if (request.captureMethod() == CaptureMethod.MANUAL) {
			canonical += "|" + CaptureMethod.MANUAL.name();
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
