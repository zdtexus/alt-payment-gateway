package com.example.payment_service.error;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

public final class DatabaseOutage {

	private static final String CONNECTION_EXCEPTION_CLASS = "08";

	static final String HIKARI_CLOSED_CONNECTION_MESSAGE = "Connection is closed";

	private static final int MAX_CAUSE_DEPTH = 20;

	private DatabaseOutage() {
	}

	public static boolean causedBy(Throwable exception) {
		Throwable current = exception;
		for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (isOutage(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static boolean isOutage(Throwable exception) {
		if (exception instanceof DataAccessResourceFailureException
				|| exception instanceof CannotCreateTransactionException
				|| exception instanceof SQLTransientConnectionException
				|| exception instanceof SQLNonTransientConnectionException) {
			return true;
		}
		if (!(exception instanceof SQLException sql)) {
			return false;
		}
		String state = sql.getSQLState();
		if (state == null) {
			return HIKARI_CLOSED_CONNECTION_MESSAGE.equals(sql.getMessage());
		}
		return state.startsWith(CONNECTION_EXCEPTION_CLASS);
	}

}
