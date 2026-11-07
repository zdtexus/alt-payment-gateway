package com.example.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.example.payment_service.entity.Refund;
import com.example.payment_service.entity.RefundStatus;

public record RefundResponse(@Schema(example = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d") UUID id,
		@Schema(example = "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b") UUID paymentId,
		@Schema(example = "20.00") BigDecimal amount, @Schema(example = "EUR") String currency,
		@Schema(description = "PENDING until the provider's result is known, then SUCCEEDED or DECLINED. "
				+ "A final status never changes again.", example = "SUCCEEDED") RefundStatus status,
		@Schema(example = "Item returned", nullable = true) String reason,
		@Schema(description = "Set only when the provider declined the refund", example = "Card account closed",
				nullable = true) String declineReason,
		@Schema(example = "2026-01-16T09:00:00Z") Instant createdAt,
		@Schema(description = "When the refund became SUCCEEDED or DECLINED; null while PENDING",
				example = "2026-01-16T09:00:00.300Z", nullable = true) Instant processedAt) {

	public static RefundResponse from(Refund refund, UUID paymentId, String currency) {
		return new RefundResponse(refund.getId(), paymentId, refund.getAmount(), currency, refund.getStatus(),
				refund.getReason(), refund.getDeclineReason(), refund.getCreatedAt(), refund.getProcessedAt());
	}

}
