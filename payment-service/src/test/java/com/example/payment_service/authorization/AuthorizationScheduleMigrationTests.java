package com.example.payment_service.authorization;

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

class AuthorizationScheduleMigrationTests {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(
			DockerImageName.parse("postgres:16-alpine"));

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID VOIDING_ID = UUID.randomUUID();

	private static final UUID AUTHORIZED_ID = UUID.randomUUID();

	private static JdbcTemplate jdbcTemplate;

	private static Instant migratedAt;

	@BeforeAll
	static void migrateWithAuthorizationsFromBeforeV12() {
		postgres.start();
		DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);

		flyway(dataSource, "11").migrate();
		insertAuthorized(VOIDING_ID, "VOID");
		insertAuthorized(AUTHORIZED_ID, null);
		migratedAt = Instant.now().minusSeconds(5);
		flyway(dataSource, "latest").migrate();
	}

	@AfterAll
	static void stopDatabase() {
		postgres.stop();
	}

	@Test
	void anInterruptedCaptureOrVoidIsDueForACheckRightAway() {
		Map<String, Object> row = row(VOIDING_ID);

		assertThat(((java.sql.Timestamp) row.get("action_requested_at")).toInstant()).isAfter(migratedAt);
		assertThat(((java.sql.Timestamp) row.get("next_check_at")).toInstant()).isBetween(migratedAt,
				migratedAt.plus(Duration.ofMinutes(1)));
		assertThat(row).containsEntry("check_attempts", 0);
	}

	@Test
	void anAuthorizationWithoutActionIsLeftAsItWas() {
		assertThat(row(AUTHORIZED_ID)).containsEntry("action_requested_at", null).containsEntry("next_check_at", null);
	}

	@Test
	void onlyPendingAndAuthorizedPaymentsMayHaveANextCheck() {
		assertThatCode(() -> jdbcTemplate.update(
				"UPDATE transactions SET next_check_at = now() WHERE id = ?", AUTHORIZED_ID)).doesNotThrowAnyException();
		for (String status : new String[] { "SUCCEEDED", "DECLINED", "VOIDED" }) {
			assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> jdbcTemplate.update("""
						INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider,
						                          next_check_at)
						VALUES (?, ?, 1.00, 'EUR', 'CARD', ?, 'simulated', now())
						""", UUID.randomUUID(), DEMO_MERCHANT_ID, status))
				.withMessageContaining("chk_transactions_next_check_only_open");
		}
	}

	@Test
	void aPendingActionAlwaysHasItsRequestTime() {
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> jdbcTemplate.update(
					"UPDATE transactions SET action_requested_at = NULL WHERE id = ?", VOIDING_ID))
			.withMessageContaining("ck_transactions_action_requested_with_pending_action");
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> jdbcTemplate.update(
					"UPDATE transactions SET action_requested_at = now() WHERE id = ?", AUTHORIZED_ID))
			.withMessageContaining("ck_transactions_action_requested_with_pending_action");
	}

	@Test
	void duePendingActionsAreFoundThroughAPartialIndex() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_transactions_pending_action_next_check'",
				String.class))
			.contains("(next_check_at)")
			.contains("WHERE (pending_action IS NOT NULL)");
	}

	private static Map<String, Object> row(UUID id) {
		return jdbcTemplate.queryForMap(
				"SELECT action_requested_at, next_check_at, check_attempts FROM transactions WHERE id = ?", id);
	}

	private static void insertAuthorized(UUID id, String pendingAction) {
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider,
				                          capture_method, authorized_amount, authorization_expires_at, pending_action,
				                          check_attempts)
				VALUES (?, ?, 49.90, 'EUR', 'CARD', 'AUTHORIZED', 'simulated', 'MANUAL', 49.90,
				        now() + interval '7 days', ?, 3)
				""", id, DEMO_MERCHANT_ID, pendingAction);
	}

	private static Flyway flyway(DataSource dataSource, String target) {
		return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
	}

}
