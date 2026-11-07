package com.example.payment_service.error;

public class PaymentNotVoidableException extends RuntimeException {

	public PaymentNotVoidableException(String message) {
		super(message);
	}

}
