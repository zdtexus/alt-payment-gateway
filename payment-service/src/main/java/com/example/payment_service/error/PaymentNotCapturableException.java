package com.example.payment_service.error;

public class PaymentNotCapturableException extends RuntimeException {

	public PaymentNotCapturableException(String message) {
		super(message);
	}

}
