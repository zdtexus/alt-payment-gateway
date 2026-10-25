package com.example.payment_service.error;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

	private final UUID paymentId;

	public PaymentNotFoundException(UUID paymentId) {
		super("Payment " + paymentId + " not found");
		this.paymentId = paymentId;
	}

	public UUID getPaymentId() {
		return paymentId;
	}

}
