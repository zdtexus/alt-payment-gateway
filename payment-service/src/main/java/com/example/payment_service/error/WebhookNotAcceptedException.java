package com.example.payment_service.error;

public class WebhookNotAcceptedException extends RuntimeException {

	public WebhookNotAcceptedException(String provider) {
		super("Payment provider '" + provider + "' does not accept webhooks");
	}

}
