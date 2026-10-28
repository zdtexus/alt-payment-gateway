package com.example.payment_service.auth;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyGeneratorTests {

	@Test
	void keyIs32RandomBytesInUrlSafeBase64WithoutPadding() {
		String key = ApiKeyGenerator.generate();

		assertThat(key).hasSize(43).matches("[A-Za-z0-9_-]{43}");
		assertThat(Base64.getUrlDecoder().decode(key)).hasSize(32);
	}

	@Test
	void keysAreUnique() {
		Set<String> keys = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			keys.add(ApiKeyGenerator.generate());
		}

		assertThat(keys).hasSize(1000);
	}

}
