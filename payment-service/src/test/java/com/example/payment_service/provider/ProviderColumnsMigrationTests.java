package com.example.payment_service.provider;

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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ProviderColumnsMigrationTests {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(
			DockerImageName.parse("postgres:16-alpine"));

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID OLD_PAYMENT_ID = UUID.randomUUID();

	private static JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void migrateWithAPaymentFromBeforeV6() {
		postgres.start();
		DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);

		flyway(dataSource, "5").migrate();
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status)
				VALUES (?, ?, 10.00, 'EUR', 'CARD', 'SUCCEEDED')
				""", OLD_PAYMENT_ID, DEMO_MERCHANT_ID);
		flyway(dataSource, "latest").migrate();
	}

	@AfterAll
	static void stopDatabase() {
		postgres.stop();
	}

	@Test
	void existingPaymentsAreAssignedToTheSimulatedProvider() {
		Map<String, Object> row = jdbcTemplate
			.queryForMap("SELECT provider, provider_reference FROM transactions WHERE id = ?", OLD_PAYMENT_ID);

		assertThat(row).containsEntry("provider", "simulated").containsEntry("provider_reference", null);
	}

	@Test
	void newPaymentsMustNameTheirProvider() {
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status)
				VALUES (?, ?, 10.00, 'EUR', 'CARD', 'PENDING')
				""", UUID.randomUUID(), DEMO_MERCHANT_ID));
	}

	@Test
	void providerReferenceIsOptionalAndHoldsUpTo255Characters() {
		insertWithReference(null);
		insertWithReference("r".repeat(255));

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insertWithReference("r".repeat(256)));
	}

	@Test
	void providerNameHoldsUpTo64Characters() {
		assertThat(jdbcTemplate.queryForObject("""
				SELECT character_maximum_length FROM information_schema.columns
				WHERE table_name = 'transactions' AND column_name = 'provider'
				""", Integer.class)).isEqualTo(64);
	}

	private static void insertWithReference(String reference) {
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider,
				                          provider_reference)
				VALUES (?, ?, 10.00, 'EUR', 'CARD', 'SUCCEEDED', 'simulated', ?)
				""", UUID.randomUUID(), DEMO_MERCHANT_ID, reference);
	}

	private static Flyway flyway(DataSource dataSource, String target) {
		return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
	}

}
