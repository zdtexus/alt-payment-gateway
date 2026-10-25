package com.example.payment_service.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import com.example.payment_service.entity.PaymentMethod;

public record CreatePaymentRequest(
		@NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
		@NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code") String currency,
		@NotNull PaymentMethod paymentMethod) {
}
