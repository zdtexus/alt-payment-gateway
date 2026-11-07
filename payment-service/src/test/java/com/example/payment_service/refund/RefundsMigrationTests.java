package com.example.payment_service.refund;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RefundsMigrationTests {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(
			DockerImageName.parse("postgres:16-alpine"));

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID OTHER_MERCHANT_ID = UUID.randomUUID();

	private static final UUID DEMO_PAYMENT_ID = UUID.randomUUID();

	private static final UUID OTHER_PAYMENT_ID = UUID.randomUUID();

	private static final String HASH = "a".repeat(64);

	private static JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void migrateWithPaymentsFromBeforeV9() {
		postgres.start();
		DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);

		flyway(dataSource, "8").migrate();
		jdbcTemplate.update("""
				INSERT INTO merchants (id, name, api_key_hash, status) VALUES (?, 'Other Shop', ?, 'ACTIVE')
				""", OTHER_MERCHANT_ID, "b".repeat(64));
		insertPayment(DEMO_PAYMENT_ID, DEMO_MERCHANT_ID);
		insertPayment(OTHER_PAYMENT_ID, OTHER_MERCHANT_ID);
		flyway(dataSource, "latest").migrate();
	}

	@AfterAll
	static void stopDatabase() {
		postgres.stop();
	}

	@Test
	void existingPaymentsCanBeRefundedByTheirMerchant() {
		assertThatCode(() -> insertRefund(DEMO_PAYMENT_ID, DEMO_MERCHANT_ID, "10.00", "PENDING", null, null))
			.doesNotThrowAnyException();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0.00", "-1.00" })
	void amountMustBePositive(String amount) {
		assertViolates("ck_refunds_amount_positive",
				() -> insertRefund(DEMO_PAYMENT_ID, DEMO_MERCHANT_ID, amount, "PENDING", null, null));
	}

	@Test
	void statusMustBeKnown() {
		assertViolates("ck_refunds_status",
				() -> insertRefund(DEMO_PAYMENT_ID, DEMO_MERCHANT_ID, "1.00", "REFUNDED", null, null));
	}

	@Test
	void idempotencyKeyAndRequestHashComeTogether() {
		assertViolates("ck_refunds_idempotency_key_has_request_hash",
				() -> insertRefund(DEMO_PAYMENT_ID, DEMO_MERCHANT_ID, "1.00", "PENDING", "key-without-hash", null));
		assertViolates("ck_refunds_idempotency_key_has_request_hash",
				() -> insertRefund(DEMO_PAYMENT_ID, DEMO_MERCHANT_ID, "1.00", "PENDING", null, HASH));
	}

	@Test
	void idempotencyKeyIsUniquePerMerchant() {
		insertRefund(DEMO_PAYMENT_ID, DEMO_MERCHANT_ID, "1.00", "PENDING", "refund-1", HASH);

		assertViolates("uk_refunds_merchant_idempotency_key",
				() -> insertRefund(DEMO_PAYMENT_ID, DEMO_MERCHANT_ID, "2.00", "PENDING", "refund-1", HASH));
		assertThatCode(() -> insertRefund(OTHER_PAYMENT_ID, OTHER_MERCHANT_ID, "1.00", "PENDING", "refund-1", HASH))
			.doesNotThrowAnyException();
	}

	@Test
	void aRefundBelongsToThePaymentsOwnMerchant() {
		assertViolates("fk_refunds_transaction_of_merchant",
				() -> insertRefund(DEMO_PAYMENT_ID, OTHER_MERCHANT_ID, "1.00", "PENDING", null, null));
		assertViolates("fk_refunds_transaction_of_merchant",
				() -> insertRefund(UUID.randomUUID(), DEMO_MERCHANT_ID, "1.00", "PENDING", null, null));
	}

	@Test
	void onlyPendingRefundsMayHaveANextCheck() {
		assertThatCode(() -> jdbcTemplate.update("""
				INSERT INTO refunds (id, transaction_id, merchant_id, amount, status, next_check_at)
				VALUES (?, ?, ?, 1.00, 'PENDING', now())
				""", UUID.randomUUID(), DEMO_PAYMENT_ID, DEMO_MERCHANT_ID)).doesNotThrowAnyException();
		assertViolates("chk_refunds_next_check_only_pending", () -> jdbcTemplate.update("""
				INSERT INTO refunds (id, transaction_id, merchant_id, amount, status, next_check_at)
				VALUES (?, ?, ?, 1.00, 'SUCCEEDED', now())
				""", UUID.randomUUID(), DEMO_PAYMENT_ID, DEMO_MERCHANT_ID));
		assertViolates("chk_refunds_check_attempts_not_negative", () -> jdbcTemplate.update("""
				INSERT INTO refunds (id, transaction_id, merchant_id, amount, status, check_attempts)
				VALUES (?, ?, ?, 1.00, 'PENDING', -1)
				""", UUID.randomUUID(), DEMO_PAYMENT_ID, DEMO_MERCHANT_ID));
	}

	@Test
	void refundsAreIndexedForListingWebhooksAndReconciliation() {
		assertThat(indexDefinition("idx_refunds_transaction_created")).contains("(transaction_id, created_at)");
		assertThat(indexDefinition("idx_refunds_provider_reference")).contains("(provider_reference)")
			.contains("WHERE (provider_reference IS NOT NULL)");
		assertThat(indexDefinition("idx_refunds_pending_next_check")).contains("(next_check_at)")
			.contains("WHERE ((status)::text = 'PENDING'::text)");
	}

	private static void assertViolates(String constraint, Runnable insert) {
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(insert::run)
			.withMessageContaining(constraint);
	}

	private static String indexDefinition(String name) {
		return jdbcTemplate.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, name);
	}

	private static void insertPayment(UUID id, UUID merchantId) {
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider)
				VALUES (?, ?, 50.00, 'EUR', 'CARD', 'SUCCEEDED', 'simulated')
				""", id, merchantId);
	}

	private static void insertRefund(UUID paymentId, UUID merchantId, String amount, String status, String key,
			String hash) {
		jdbcTemplate.update("""
				INSERT INTO refunds (id, transaction_id, merchant_id, amount, status, idempotency_key, request_hash)
				VALUES (?, ?, ?, ?::numeric, ?, ?, ?)
				""", UUID.randomUUID(), paymentId, merchantId, amount, status, key, hash);
	}

	private static Flyway flyway(DataSource dataSource, String target) {
		return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
	}

}
