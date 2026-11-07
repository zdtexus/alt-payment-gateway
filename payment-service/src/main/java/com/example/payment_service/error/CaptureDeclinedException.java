package com.example.payment_service.error;

public class CaptureDeclinedException extends RuntimeException {

	public CaptureDeclinedException(String reason) {
		super("The payment provider declined the capture: " + reason + ". The payment stays AUTHORIZED");
	}

}
