package com.example.payment_service.auth;

import java.security.SecureRandom;
import java.util.Base64;

public final class ApiKeyGenerator {

	private static final int KEY_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private ApiKeyGenerator() {
	}

	public static String generate() {
		byte[] bytes = new byte[KEY_BYTES];
		RANDOM.nextBytes(bytes);
		return ENCODER.encodeToString(bytes);
	}

}
