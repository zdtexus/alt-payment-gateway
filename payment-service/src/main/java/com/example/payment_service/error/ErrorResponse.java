package com.example.payment_service.error;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.http.HttpStatus;

public record ErrorResponse(@Schema(example = "2026-01-15T10:30:00Z") Instant timestamp,
		@Schema(example = "422") int status, @Schema(example = "PAYMENT_RULE_VIOLATION") String error,
		@Schema(example = "Amount 600.00 exceeds BONUS limit 500.00") String message,
		@Schema(example = "/api/v1/payments") String path) {

	public static ErrorResponse of(HttpStatus status, String error, String message, String path) {
		return new ErrorResponse(Instant.now(), status.value(), error, message, path);
	}

}
