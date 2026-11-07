package com.example.payment_service.authorization;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AuthorizationColumnsMigrationTests {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(
			DockerImageName.parse("postgres:16-alpine"));

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID OLD_PAYMENT_ID = UUID.randomUUID();

	private static JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void migrateWithAPaymentFromBeforeV10() {
		postgres.start();
		DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);

		flyway(dataSource, "9").migrate();
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider)
				VALUES (?, ?, 49.90, 'EUR', 'CARD', 'SUCCEEDED', 'simulated')
				""", OLD_PAYMENT_ID, DEMO_MERCHANT_ID);
		flyway(dataSource, "latest").migrate();
	}

	@AfterAll
	static void stopDatabase() {
		postgres.stop();
	}

	@Test
	void existingPaymentsAreAutomaticWithoutAuthorization() {
		Map<String, Object> row = jdbcTemplate.queryForMap("""
				SELECT capture_method, authorized_amount, captured_amount, authorization_expires_at
				FROM transactions WHERE id = ?
				""", OLD_PAYMENT_ID);

		assertThat(row).containsEntry("capture_method", "AUTOMATIC")
			.containsEntry("authorized_amount", null)
			.containsEntry("captured_amount", null)
			.containsEntry("authorization_expires_at", null);
	}

	@Test
	void aPaymentWithoutCaptureMethodIsAutomatic() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider)
				VALUES (?, ?, 10.00, 'EUR', 'CARD', 'PENDING', 'simulated')
				""", id, DEMO_MERCHANT_ID);

		assertThat(jdbcTemplate.queryForObject("SELECT capture_method FROM transactions WHERE id = ?", String.class, id))
			.isEqualTo("AUTOMATIC");
	}

	@Test
	void aManualPaymentStoresItsAuthorization() {
		assertThatCode(() -> insert("MANUAL", "AUTHORIZED", "49.90", null, "now() + interval '7 days'"))
			.doesNotThrowAnyException();
		assertThatCode(() -> insert("MANUAL", "SUCCEEDED", "49.90", "30.00", "now()")).doesNotThrowAnyException();
		assertThatCode(() -> insert("MANUAL", "VOIDED", "49.90", null, "now()")).doesNotThrowAnyException();
		assertThatCode(() -> insert("MANUAL", "PENDING", null, null, "NULL")).doesNotThrowAnyException();
	}

	@Test
	void captureMethodMustBeKnown() {
		assertViolates("ck_transactions_capture_method", () -> insert("LATER", "PENDING", null, null, "NULL"));
	}

	@Test
	void anAutomaticPaymentHasNoAuthorization() {
		assertViolates("ck_transactions_automatic_has_no_authorization",
				() -> insert("AUTOMATIC", "SUCCEEDED", "49.90", null, "NULL"));
		assertViolates("ck_transactions_automatic_has_no_authorization",
				() -> insert("AUTOMATIC", "SUCCEEDED", null, null, "now()"));
	}

	@Test
	void capturedAmountIsPositiveAndAtMostTheAuthorizedOne() {
		assertViolates("ck_transactions_authorization_amounts",
				() -> insert("MANUAL", "SUCCEEDED", "49.90", "49.91", "now()"));
		assertViolates("ck_transactions_authorization_amounts", () -> insert("MANUAL", "SUCCEEDED", null, "10.00", "NULL"));
		assertViolates("ck_transactions_authorization_amounts", () -> insert("MANUAL", "AUTHORIZED", "0.00", null, "now()"));
		assertViolates("ck_transactions_authorization_amounts",
				() -> insert("MANUAL", "SUCCEEDED", "49.90", "0.00", "now()"));
		assertThatCode(() -> insert("MANUAL", "SUCCEEDED", "49.90", "49.90", "now()")).doesNotThrowAnyException();
	}

	@Test
	void authorizationsAreIndexedByExpiryWhileAuthorized() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_transactions_authorized_expiry'", String.class))
			.contains("(authorization_expires_at)")
			.contains("WHERE ((status)::text = 'AUTHORIZED'::text)");
	}

	private static void assertViolates(String constraint, Runnable insert) {
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(insert::run)
			.withMessageContaining(constraint);
	}

	private static void insert(String captureMethod, String status, String authorized, String captured,
			String expiresAt) {
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider,
				                          capture_method, authorized_amount, captured_amount, authorization_expires_at)
				VALUES (?, ?, 49.90, 'EUR', 'CARD', ?, 'simulated', ?, ?::numeric, ?::numeric, %s)
				""".formatted(expiresAt), UUID.randomUUID(), DEMO_MERCHANT_ID, status, captureMethod, authorized, captured);
	}

	private static Flyway flyway(DataSource dataSource, String target) {
		return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
	}

}
