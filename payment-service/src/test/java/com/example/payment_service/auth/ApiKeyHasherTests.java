package com.example.payment_service.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHasherTests {

	@Test
	void matchesSha256TestVector() {
		assertThat(ApiKeyHasher.hash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	void hashesDemoKey() {
		assertThat(ApiKeyHasher.hash("demo-key-123"))
			.isEqualTo("a52782e3a2d4dd2f95f640b9abfb3b2a6b8e722c65f55be830f6e5f619f7f873");
	}

	@Test
	void hashesUtf8Bytes() {
		assertThat(ApiKeyHasher.hash("ключ"))
			.isEqualTo("1de36a32af798da0c1ac9297603a320ed8fe567cf21c9177112a4ce914ebb8be");
	}

	@Test
	void producesLowercaseHexOf64Characters() {
		assertThat(ApiKeyHasher.hash("ANY-Key_42")).matches("[0-9a-f]{64}");
	}

	@Test
	void differentKeysGiveDifferentHashes() {
		assertThat(ApiKeyHasher.hash("demo-key-123")).isNotEqualTo(ApiKeyHasher.hash("demo-key-124"));
	}

}
