package com.example.payment_service.reconciliation;

import java.time.Duration;
import java.time.Instant;
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

class PendingCheckMigrationTests {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(
			DockerImageName.parse("postgres:16-alpine"));

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID OLD_PENDING_ID = UUID.randomUUID();

	private static final UUID OLD_FINAL_ID = UUID.randomUUID();

	private static JdbcTemplate jdbcTemplate;

	private static Instant migratedAt;

	@BeforeAll
	static void migrateWithPaymentsFromBeforeV8() {
		postgres.start();
		DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);

		flyway(dataSource, "7").migrate();
		insertOld(OLD_PENDING_ID, "PENDING");
		insertOld(OLD_FINAL_ID, "SUCCEEDED");
		migratedAt = jdbcTemplate.queryForObject("SELECT now()", Instant.class);
		flyway(dataSource, "latest").migrate();
	}

	@AfterAll
	static void stopDatabase() {
		postgres.stop();
	}

	@Test
	void existingPendingPaymentsAreDueForACheckRightAway() {
		Map<String, Object> row = row(OLD_PENDING_ID);

		assertThat(((java.sql.Timestamp) row.get("next_check_at")).toInstant()).isBetween(migratedAt,
				migratedAt.plus(Duration.ofMinutes(1)));
		assertThat(row).containsEntry("check_attempts", 0);
	}

	@Test
	void existingFinalPaymentsAreNeverDue() {
		assertThat(row(OLD_FINAL_ID)).containsEntry("next_check_at", null).containsEntry("check_attempts", 0);
	}

	@Test
	void onlyPendingPaymentsMayHaveANextCheck() {
		assertThatCode(() -> insert("PENDING", "now()", 0)).doesNotThrowAnyException();
		assertThatCode(() -> insert("DECLINED", "NULL", 2)).doesNotThrowAnyException();
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insert("SUCCEEDED", "now()", 0))
			.withMessageContaining("chk_transactions_next_check_only_pending");
	}

	@Test
	void checkAttemptsCannotBeNegative() {
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insert("PENDING", "now()", -1))
			.withMessageContaining("chk_transactions_check_attempts_not_negative");
	}

	@Test
	void duePendingPaymentsAreFoundThroughAPartialIndex() {
		assertThat(jdbcTemplate.queryForObject("""
				SELECT indexdef FROM pg_indexes
				WHERE tablename = 'transactions' AND indexname = 'idx_transactions_pending_next_check'
				""", String.class)).contains("(next_check_at)").contains("WHERE ((status)::text = 'PENDING'::text)");
	}

	private static Map<String, Object> row(UUID id) {
		return jdbcTemplate.queryForMap("SELECT next_check_at, check_attempts FROM transactions WHERE id = ?", id);
	}

	private static void insertOld(UUID id, String status) {
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider)
				VALUES (?, ?, 10.00, 'EUR', 'CARD', ?, 'http')
				""", id, DEMO_MERCHANT_ID, status);
	}

	private static void insert(String status, String nextCheckAt, int attempts) {
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider,
				                          next_check_at, check_attempts)
				VALUES (?, ?, 10.00, 'EUR', 'CARD', ?, 'http', %s, ?)
				""".formatted(nextCheckAt), UUID.randomUUID(), DEMO_MERCHANT_ID, status, attempts);
	}

	private static Flyway flyway(DataSource dataSource, String target) {
		return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
	}

}
