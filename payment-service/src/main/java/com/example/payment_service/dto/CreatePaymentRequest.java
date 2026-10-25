package com.example.payment_service.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import com.example.payment_service.entity.PaymentMethod;

public record CreatePaymentRequest(
		@Schema(description = "Amount with at most 2 decimals; limits depend on the payment method",
				example = "49.90") @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
		@Schema(description = "ISO 4217 currency code; allowed currencies depend on the payment method",
				example = "EUR") @NotNull @Pattern(regexp = "[A-Z]{3}",
						message = "must be a 3-letter ISO currency code") String currency,
		@Schema(example = "CARD") @NotNull PaymentMethod paymentMethod) {
}
