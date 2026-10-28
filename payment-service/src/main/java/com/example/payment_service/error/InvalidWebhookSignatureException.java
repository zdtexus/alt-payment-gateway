package com.example.payment_service.error;

public class InvalidWebhookSignatureException extends RuntimeException {

	public InvalidWebhookSignatureException() {
		super("Missing or invalid webhook signature");
	}

}
