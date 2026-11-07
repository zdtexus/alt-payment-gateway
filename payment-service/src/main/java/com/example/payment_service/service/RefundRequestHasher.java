package com.example.payment_service.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class RefundRequestHasher {

	private RefundRequestHasher() {
	}

	static String hash(UUID paymentId, BigDecimal amount, String reason) {
		String canonical = String.join("|", "refund", paymentId.toString(),
				(amount != null) ? amount.stripTrailingZeros().toPlainString() : "remaining",
				(reason != null) ? reason : "");
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
