package com.example.payment_service.error;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseOutageTests {

	static Stream<Throwable> outages() {
		return Stream.of(new DataAccessResourceFailureException("Connection refused"),
				new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", new SQLException("refused")),
				new CannotCreateTransactionException("Could not open JPA EntityManager"),
				new JpaSystemException(new RuntimeException("Unable to rollback against JDBC Connection",
						new SQLException("Connection is closed"))),
				new SQLException("This connection has been closed.", "08003"),
				new TransactionSystemException("Could not commit JPA transaction",
						new SQLException("An I/O error occurred while sending to the backend", "08006")),
				new SQLException("Connection attempt failed", "08001"),
				new SQLTransientConnectionException("HikariPool-1 - Connection is not available"),
				new SQLNonTransientConnectionException("Connection reset"),
				new IllegalStateException("wrapped", new RuntimeException("twice",
						new DataAccessResourceFailureException("Connection refused"))));
	}

	static Stream<Throwable> notOutages() {
		return Stream.of(new IllegalStateException("bug"),
				new DataIntegrityViolationException("duplicate key",
						new SQLException("duplicate key value violates unique constraint", "23505")),
				new JpaSystemException(new RuntimeException("no state", new SQLException("unknown"))),
				new SQLException("Connection is closed", "23505"),
				new SQLException("syntax error", "42601"));
	}

	@ParameterizedTest
	@MethodSource("outages")
	void recognisesLostOrUnreachableDatabase(Throwable exception) {
		assertThat(DatabaseOutage.causedBy(exception)).isTrue();
	}

	@ParameterizedTest
	@MethodSource("notOutages")
	void otherDatabaseErrorsAndBugsAreNotOutages(Throwable exception) {
		assertThat(DatabaseOutage.causedBy(exception)).isFalse();
	}

	@Test
	void nullIsNotAnOutage() {
		assertThat(DatabaseOutage.causedBy(null)).isFalse();
	}

	@Test
	void causeCycleEnds() {
		RuntimeException first = new RuntimeException("first");
		RuntimeException second = new RuntimeException("second", first);
		first.initCause(second);

		assertThat(DatabaseOutage.causedBy(first)).isFalse();
	}

}
