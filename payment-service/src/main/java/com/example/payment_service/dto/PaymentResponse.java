package com.example.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.example.payment_service.entity.CaptureMethod;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.PendingAction;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;

public record PaymentResponse(@Schema(example = "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b") UUID id,
		@Schema(example = "49.90") BigDecimal amount,
		@Schema(description = "Sum of the payment's SUCCEEDED refunds; 0.00 when nothing was refunded. "
				+ "The status stays SUCCEEDED when a payment is refunded.", example = "20.00") BigDecimal refundedAmount,
		@Schema(example = "EUR") String currency,
		@Schema(example = "CARD") PaymentMethod paymentMethod,
		@Schema(description = "AUTOMATIC or MANUAL, as requested", example = "AUTOMATIC") CaptureMethod captureMethod,
		@Schema(description = "PENDING until the provider's result is known, then SUCCEEDED or DECLINED. "
				+ "A final status never changes again.", example = "SUCCEEDED") TransactionStatus status,
		@Schema(description = "Set only when the payment was declined", example = "Insufficient funds",
				nullable = true) String declineReason,
		@Schema(example = "2026-01-15T10:30:00Z") Instant createdAt,
		@Schema(description = "When the payment became SUCCEEDED or DECLINED; null while PENDING",
				example = "2026-01-15T10:30:00.300Z", nullable = true) Instant processedAt,
		@Schema(description = "Set only for MANUAL payments once authorized: the reserved amount must be captured "
				+ "before this time", example = "2026-01-22T10:30:00.300Z",
				nullable = true) Instant authorizationExpiresAt,
		@Schema(description = "Set only for MANUAL payments once captured; at most the authorized amount",
				example = "30.00", nullable = true) BigDecimal capturedAmount,
		@Schema(description = "CAPTURE or VOID while that request waits for the provider's answer; "
				+ "the payment stays AUTHORIZED until then", example = "CAPTURE",
				nullable = true) PendingAction pendingAction) {

	public static final BigDecimal NOTHING_REFUNDED = new BigDecimal("0.00");

	public static PaymentResponse from(Transaction transaction, BigDecimal refundedAmount) {
		return new PaymentResponse(transaction.getId(), transaction.getAmount(), refundedAmount,
				transaction.getCurrency(),
				transaction.getPaymentMethod(), transaction.getCaptureMethod(), transaction.getStatus(),
				transaction.getDeclineReason(), transaction.getCreatedAt(), transaction.getProcessedAt(),
				transaction.getAuthorizationExpiresAt(), transaction.getCapturedAmount(),
				transaction.getPendingAction());
	}

}
