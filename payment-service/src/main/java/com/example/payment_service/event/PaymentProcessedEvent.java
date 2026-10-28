package com.example.payment_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;

public record PaymentProcessedEvent(UUID paymentId, String merchantName, String merchantEmail, String merchantPhone,
		BigDecimal amount, String currency, PaymentMethod paymentMethod, String provider, TransactionStatus status,
		String declineReason, Instant processedAt) {

	public static PaymentProcessedEvent from(Transaction transaction) {
		Merchant merchant = transaction.getMerchant();
		return new PaymentProcessedEvent(transaction.getId(), merchant.getName(), merchant.getEmail(),
				merchant.getPhone(), transaction.getAmount(), transaction.getCurrency(),
				transaction.getPaymentMethod(), transaction.getProvider(), transaction.getStatus(),
				transaction.getDeclineReason(), transaction.getProcessedAt());
	}

}
