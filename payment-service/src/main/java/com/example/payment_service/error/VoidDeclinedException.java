package com.example.payment_service.error;

public class VoidDeclinedException extends RuntimeException {

	public VoidDeclinedException(String reason) {
		super("The payment provider declined the void: " + reason + ". The payment stays AUTHORIZED");
	}

}
