package com.example.payment_service.auth;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.payment_service.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApiKeyHashMigrationTests {

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void backfillsDemoMerchantHashMatchingApiKeyHasher() {
		String hash = jdbcTemplate.queryForObject("SELECT api_key_hash FROM merchants WHERE id = ?", String.class,
				DEMO_MERCHANT_ID);

		assertThat(hash).isEqualTo(ApiKeyHasher.hash("demo-key-123"));
	}

	@Test
	void postgresSha256MatchesApiKeyHasherForUtf8Keys() {
		String key = "ключ-42";
		String hash = jdbcTemplate.queryForObject("SELECT encode(sha256(convert_to(?, 'UTF8')), 'hex')", String.class,
				key);

		assertThat(hash).isEqualTo(ApiKeyHasher.hash(key));
	}

	@Test
	void hashIsUnique() {
		String demoHash = ApiKeyHasher.hash("demo-key-123");

		assertThatExceptionOfType(DuplicateKeyException.class).isThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO merchants (id, name, api_key, api_key_hash, status) VALUES (?, 'Copy', 'copy-key', ?, 'ACTIVE')",
				UUID.randomUUID(), demoHash));
	}

}
