package com.example.payment_service.provider;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.provider.http")
public record HttpPaymentProviderProperties(URI url, String apiKey,
		@DefaultValue("2s") @NotNull Duration connectTimeout, @DefaultValue("10s") @NotNull Duration readTimeout,
		String webhookSecret) {

	static final int MIN_WEBHOOK_SECRET_LENGTH = 16;

	@AssertTrue(message = "payment.provider.http connect-timeout and read-timeout must be positive")
	public boolean isTimeoutsPositive() {
		return connectTimeout != null && readTimeout != null && connectTimeout.isPositive()
				&& readTimeout.isPositive();
	}

	@AssertTrue(message = "payment.provider.http.webhook-secret must be empty or at least "
			+ MIN_WEBHOOK_SECRET_LENGTH + " characters")
	public boolean isWebhookSecretLongEnough() {
		return !StringUtils.hasText(webhookSecret) || webhookSecret.length() >= MIN_WEBHOOK_SECRET_LENGTH;
	}

}
