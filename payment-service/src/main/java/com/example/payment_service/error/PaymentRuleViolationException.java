package com.example.payment_service.error;

public class PaymentRuleViolationException extends RuntimeException {

	public PaymentRuleViolationException(String message) {
		super(message);
	}

}
