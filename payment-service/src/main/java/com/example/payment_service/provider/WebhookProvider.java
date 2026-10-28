package com.example.payment_service.provider;

public interface WebhookProvider {

	boolean acceptsWebhooks();

	ProviderWebhook readWebhook(byte[] body, String signature);

}
