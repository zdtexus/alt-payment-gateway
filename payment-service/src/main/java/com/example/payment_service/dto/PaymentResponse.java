package com.example.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;

public record PaymentResponse(UUID id, BigDecimal amount, String currency, PaymentMethod paymentMethod,
		TransactionStatus status, String declineReason, Instant createdAt, Instant processedAt) {

	public static PaymentResponse from(Transaction transaction) {
		return new PaymentResponse(transaction.getId(), transaction.getAmount(), transaction.getCurrency(),
				transaction.getPaymentMethod(), transaction.getStatus(), transaction.getDeclineReason(),
				transaction.getCreatedAt(), transaction.getProcessedAt());
	}

}
