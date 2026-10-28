package com.example.payment_service.error;

public class WebhookPaymentNotFoundException extends RuntimeException {

	public WebhookPaymentNotFoundException(String provider, String reference) {
		super("No payment of provider '" + provider + "' matches reference '" + reference + "'");
	}

}
