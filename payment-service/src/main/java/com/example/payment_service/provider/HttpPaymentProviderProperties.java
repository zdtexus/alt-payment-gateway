package com.example.payment_service.provider;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.provider.http")
public record HttpPaymentProviderProperties(URI url, String apiKey,
		@DefaultValue("2s") @NotNull Duration connectTimeout, @DefaultValue("10s") @NotNull Duration readTimeout,
		String webhookSecret, URI statusUrl, URI refundUrl, URI authorizeUrl, URI captureUrl, URI voidUrl) {

	static final int MIN_WEBHOOK_SECRET_LENGTH = 16;

	@ConstructorBinding
	public HttpPaymentProviderProperties {
		if (statusUrl != null && statusUrl.toString().isBlank()) {
			statusUrl = null;
		}
		if (refundUrl != null && refundUrl.toString().isBlank()) {
			refundUrl = null;
		}
		authorizeUrl = blankToNull(authorizeUrl);
		captureUrl = blankToNull(captureUrl);
		voidUrl = blankToNull(voidUrl);
	}

	public HttpPaymentProviderProperties(URI url, String apiKey, Duration connectTimeout, Duration readTimeout,
			String webhookSecret, URI statusUrl, URI refundUrl) {
		this(url, apiKey, connectTimeout, readTimeout, webhookSecret, statusUrl, refundUrl, null, null, null);
	}

	private static URI blankToNull(URI uri) {
		return (uri != null && uri.toString().isBlank()) ? null : uri;
	}

	@AssertTrue(message = "payment.provider.http authorize-url, capture-url and void-url must be set together")
	public boolean isManualCaptureComplete() {
		int set = ((authorizeUrl != null) ? 1 : 0) + ((captureUrl != null) ? 1 : 0) + ((voidUrl != null) ? 1 : 0);
		return set == 0 || set == 3;
	}

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

	@Override
	public String toString() {
		return "HttpPaymentProviderProperties[url=" + url + ", apiKey=" + mask(apiKey) + ", connectTimeout="
				+ connectTimeout + ", readTimeout=" + readTimeout + ", webhookSecret=" + mask(webhookSecret)
				+ ", statusUrl=" + statusUrl + ", refundUrl=" + refundUrl + ", authorizeUrl=" + authorizeUrl
				+ ", captureUrl=" + captureUrl + ", voidUrl=" + voidUrl + "]";
	}

	private static String mask(String secret) {
		return StringUtils.hasText(secret) ? "***" : null;
	}

}
