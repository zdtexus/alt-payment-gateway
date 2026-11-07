package com.example.payment_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.Refund;
import com.example.payment_service.entity.RefundStatus;
import com.example.payment_service.entity.Transaction;

public record RefundProcessedEvent(UUID refundId, UUID paymentId, String merchantName, String merchantEmail,
		String merchantPhone, BigDecimal amount, String currency, RefundStatus status, String declineReason,
		Instant processedAt) {

	public static RefundProcessedEvent from(Refund refund) {
		Transaction payment = refund.getTransaction();
		Merchant merchant = refund.getMerchant();
		return new RefundProcessedEvent(refund.getId(), payment.getId(), merchant.getName(), merchant.getEmail(),
				merchant.getPhone(), refund.getAmount(), payment.getCurrency(), refund.getStatus(),
				refund.getDeclineReason(), refund.getProcessedAt());
	}

}
