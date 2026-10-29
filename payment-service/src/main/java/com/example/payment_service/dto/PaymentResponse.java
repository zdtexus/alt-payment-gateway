package com.example.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;

public record PaymentResponse(@Schema(example = "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b") UUID id,
		@Schema(example = "49.90") BigDecimal amount, @Schema(example = "EUR") String currency,
		@Schema(example = "CARD") PaymentMethod paymentMethod,
		@Schema(description = "PENDING until the provider's result is known, then SUCCEEDED or DECLINED. "
				+ "A final status never changes again.", example = "SUCCEEDED") TransactionStatus status,
		@Schema(description = "Set only when the payment was declined", example = "Insufficient funds",
				nullable = true) String declineReason,
		@Schema(example = "2026-01-15T10:30:00Z") Instant createdAt,
		@Schema(description = "When the payment became SUCCEEDED or DECLINED; null while PENDING",
				example = "2026-01-15T10:30:00.300Z", nullable = true) Instant processedAt) {

	public static PaymentResponse from(Transaction transaction) {
		return new PaymentResponse(transaction.getId(), transaction.getAmount(), transaction.getCurrency(),
				transaction.getPaymentMethod(), transaction.getStatus(), transaction.getDeclineReason(),
				transaction.getCreatedAt(), transaction.getProcessedAt());
	}

}
