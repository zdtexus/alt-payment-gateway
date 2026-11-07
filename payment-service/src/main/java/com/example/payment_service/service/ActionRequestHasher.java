package com.example.payment_service.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.payment_service.entity.PendingAction;

final class ActionRequestHasher {

	private ActionRequestHasher() {
	}

	static String hash(PendingAction action, BigDecimal amount) {
		String canonical = action.name() + "|" + ((amount != null) ? amount.stripTrailingZeros().toPlainString() : "");
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
