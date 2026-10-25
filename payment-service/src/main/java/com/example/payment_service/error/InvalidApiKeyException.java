package com.example.payment_service.error;

public class InvalidApiKeyException extends RuntimeException {

	public InvalidApiKeyException() {
		super("Missing or unknown API key");
	}

}
