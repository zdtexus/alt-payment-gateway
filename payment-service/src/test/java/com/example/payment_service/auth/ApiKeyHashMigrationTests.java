package com.example.payment_service.auth;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
				"INSERT INTO merchants (id, name, api_key_hash, status) VALUES (?, 'Copy', ?, 'ACTIVE')",
				UUID.randomUUID(), demoHash));
	}

	@Test
	void hashIsRequired() {
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbcTemplate
			.update("INSERT INTO merchants (id, name, status) VALUES (?, 'No Key', 'ACTIVE')", UUID.randomUUID()));
	}

	@Test
	void plainTextKeyColumnIsDropped() {
		List<String> columns = jdbcTemplate.queryForList(
				"SELECT column_name FROM information_schema.columns WHERE table_name = 'merchants'", String.class);

		assertThat(columns).contains("api_key_hash").doesNotContain("api_key");
	}

	@Test
	void plainTextKeyIsNotStoredAnywhereInMerchantRow() {
		String row = jdbcTemplate.queryForObject("SELECT row_to_json(m)::text FROM merchants m WHERE id = ?",
				String.class, DEMO_MERCHANT_ID);

		assertThat(row).doesNotContain("demo-key-123").contains(ApiKeyHasher.hash("demo-key-123"));
	}

}
