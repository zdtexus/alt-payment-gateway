package com.example.payment_service.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

public record CapturePaymentRequest(
		@Schema(description = "Amount to capture, at most 2 decimals and at most the authorized amount. "
				+ "Leave it out to capture the whole authorized amount.", example = "30.00",
				nullable = true) @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {
}
