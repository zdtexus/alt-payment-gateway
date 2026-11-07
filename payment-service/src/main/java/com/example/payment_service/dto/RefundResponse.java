package com.example.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.payment_service.entity.Refund;
import com.example.payment_service.entity.RefundStatus;

public record RefundResponse(UUID id, UUID paymentId, BigDecimal amount, String currency, RefundStatus status,
		String reason, String declineReason, Instant createdAt, Instant processedAt) {

	public static RefundResponse from(Refund refund, UUID paymentId, String currency) {
		return new RefundResponse(refund.getId(), paymentId, refund.getAmount(), currency, refund.getStatus(),
				refund.getReason(), refund.getDeclineReason(), refund.getCreatedAt(), refund.getProcessedAt());
	}

}
