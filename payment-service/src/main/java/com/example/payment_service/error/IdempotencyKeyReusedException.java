package com.example.payment_service.error;

public class IdempotencyKeyReusedException extends RuntimeException {

	public IdempotencyKeyReusedException() {
		super("Idempotency key was already used with a different request");
	}

}
