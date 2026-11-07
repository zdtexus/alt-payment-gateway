package com.example.payment_service.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateRefundRequest(
		@Schema(description = "Amount to refund, at most 2 decimals. Leave it out to refund everything that "
				+ "is still refundable.", example = "20.00", nullable = true) @Positive @Digits(integer = 17,
						fraction = 2) BigDecimal amount,
		@Schema(description = "Why the payment is refunded, at most 255 characters", example = "Item returned",
				nullable = true) @Size(max = 255) String reason) {
}
