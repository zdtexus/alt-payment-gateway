package com.example.payment_service.error;

public class InvalidWebhookException extends RuntimeException {

	public InvalidWebhookException(String message) {
		super(message);
	}

	public InvalidWebhookException(String message, Throwable cause) {
		super(message, cause);
	}

}
