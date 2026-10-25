package com.example.payment_service.error;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class ErrorResponseJsonTests {

	@Autowired
	private JacksonTester<ErrorResponse> json;

	@Test
	void serializesInCommonErrorFormat() throws Exception {
		ErrorResponse response = new ErrorResponse(Instant.parse("2026-01-15T10:30:00Z"), 422,
				"PAYMENT_RULE_VIOLATION", "Amount 600.00 exceeds BONUS limit 500.00", "/api/v1/payments");

		assertThat(json.write(response)).isEqualToJson("""
				{
				  "timestamp": "2026-01-15T10:30:00Z",
				  "status": 422,
				  "error": "PAYMENT_RULE_VIOLATION",
				  "message": "Amount 600.00 exceeds BONUS limit 500.00",
				  "path": "/api/v1/payments"
				}
				""");
	}

	@Test
	void ofFillsTimestampAndStatusCode() {
		Instant before = Instant.now();

		ErrorResponse response = ErrorResponse.of(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found",
				"/api/v1/payments/42");

		assertThat(response.timestamp()).isBetween(before, Instant.now());
		assertThat(response.status()).isEqualTo(404);
		assertThat(response.error()).isEqualTo("PAYMENT_NOT_FOUND");
		assertThat(response.message()).isEqualTo("Payment not found");
		assertThat(response.path()).isEqualTo("/api/v1/payments/42");
	}

}
