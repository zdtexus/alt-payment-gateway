package com.example.payment_service.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class IdempotencyMigrationTests {

	private static final String REQUEST_HASH = "a".repeat(64);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void transactionsWithoutKeyAreStillAllowed() {
		UUID merchantId = insertMerchant();

		insertTransaction(merchantId, null, null);
		insertTransaction(merchantId, null, null);

		Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE merchant_id = ?",
				Integer.class, merchantId);
		assertThat(count).isEqualTo(2);
	}

	@Test
	void keyIsUniquePerMerchant() {
		UUID merchantId = insertMerchant();
		insertTransaction(merchantId, "order-1", REQUEST_HASH);

		assertThatExceptionOfType(DuplicateKeyException.class)
			.isThrownBy(() -> insertTransaction(merchantId, "order-1", REQUEST_HASH));
	}

	@Test
	void differentMerchantsCanUseTheSameKey() {
		insertTransaction(insertMerchant(), "order-1", REQUEST_HASH);

		assertThatNoException().isThrownBy(() -> insertTransaction(insertMerchant(), "order-1", REQUEST_HASH));
	}

	@Test
	void keyRequiresRequestHash() {
		UUID merchantId = insertMerchant();

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insertTransaction(merchantId, "order-1", null));
	}

	@Test
	void requestHashRequiresKey() {
		UUID merchantId = insertMerchant();

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insertTransaction(merchantId, null, REQUEST_HASH));
	}

	private UUID insertMerchant() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO merchants (id, name, api_key_hash, status) VALUES (?, 'Shop', ?, 'ACTIVE')", id,
				ApiKeyHasher.hash(id.toString()));
		return id;
	}

	private void insertTransaction(UUID merchantId, String idempotencyKey, String requestHash) {
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status,
				                          idempotency_key, request_hash, provider)
				VALUES (?, ?, 10.00, 'EUR', 'CARD', 'PENDING', ?, ?, 'simulated')
				""", UUID.randomUUID(), merchantId, idempotencyKey, requestHash);
	}

}
