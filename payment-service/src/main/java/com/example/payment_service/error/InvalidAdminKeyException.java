package com.example.payment_service.error;

public class InvalidAdminKeyException extends RuntimeException {

	public InvalidAdminKeyException() {
		super("Missing or invalid admin key");
	}

}
