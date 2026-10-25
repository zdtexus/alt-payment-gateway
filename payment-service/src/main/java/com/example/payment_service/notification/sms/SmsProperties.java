package com.example.payment_service.notification.sms;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("notification.sms")
public record SmsProperties(@DefaultValue("log") @NotNull Provider provider, @DefaultValue @Valid Http http) {

	@AssertTrue(message = "notification.sms.http.url is required when the provider is http")
	public boolean isHttpUrlPresentWhenNeeded() {
		return provider != Provider.HTTP || http.url() != null;
	}

	public enum Provider {

		LOG, HTTP

	}

	public record Http(URI url, String apiKey, @DefaultValue("5s") @NotNull Duration timeout) {
	}

}
