package com.example.payment_service.provider;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.provider.http")
public record HttpPaymentProviderProperties(URI url, String apiKey,
		@DefaultValue("2s") @NotNull Duration connectTimeout, @DefaultValue("10s") @NotNull Duration readTimeout) {

	@AssertTrue(message = "payment.provider.http connect-timeout and read-timeout must be positive")
	public boolean isTimeoutsPositive() {
		return connectTimeout != null && readTimeout != null && connectTimeout.isPositive()
				&& readTimeout.isPositive();
	}

}
