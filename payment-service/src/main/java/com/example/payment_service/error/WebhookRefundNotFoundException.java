package com.example.payment_service.error;

public class WebhookRefundNotFoundException extends RuntimeException {

	public WebhookRefundNotFoundException(String provider, String reference) {
		super("No refund of provider " + provider + " matches reference " + reference);
	}

}
