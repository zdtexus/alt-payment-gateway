package com.example.payment_service.provider;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.example.payment_service.error.InvalidWebhookException;
import com.example.payment_service.error.InvalidWebhookSignatureException;

@Component
@ConditionalOnProperty(name = "payment.provider.http.url")
public class HttpPaymentProvider implements PaymentProvider, WebhookProvider {

	public static final String NAME = "http";

	static final String SIGNATURE_PREFIX = "sha256=";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RestClient restClient;

	private final String webhookSecret;

	public HttpPaymentProvider(HttpPaymentProviderProperties properties) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient(properties));
		requestFactory.setReadTimeout(properties.readTimeout());
		RestClient.Builder builder = RestClient.builder()
			.baseUrl(properties.url().toString())
			.requestFactory(new BufferingClientHttpRequestFactory(requestFactory));
		if (StringUtils.hasText(properties.apiKey())) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
		}
		this.restClient = builder.build();
		this.webhookSecret = StringUtils.hasText(properties.webhookSecret()) ? properties.webhookSecret() : null;
	}

	static HttpClient httpClient(HttpPaymentProviderProperties properties) {
		return HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(properties.connectTimeout())
			.build();
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public ProviderResult process(ProviderRequest request) {
		ProviderAnswer answer = restClient.post()
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", request.paymentId().toString())
			.body(Map.of("paymentId", request.paymentId(), "method", request.method(), "currency",
					request.currency(), "amount", request.amount()))
			.retrieve()
			.body(ProviderAnswer.class);
		if (answer == null) {
			throw new IllegalStateException("Payment provider http answered without a status");
		}
		return answer.toResult();
	}

	@Override
	public boolean acceptsWebhooks() {
		return webhookSecret != null;
	}

	@Override
	public ProviderWebhook readWebhook(byte[] body, String signature) {
		if (!acceptsWebhooks()) {
			throw new IllegalStateException("Payment provider http has no webhook secret");
		}
		if (!MessageDigest.isEqual(sign(webhookSecret, body).getBytes(StandardCharsets.US_ASCII),
				(signature != null) ? signature.getBytes(StandardCharsets.US_ASCII) : new byte[0])) {
			throw new InvalidWebhookSignatureException();
		}
		WebhookBody webhook;
		try {
			webhook = JSON.readValue(body, WebhookBody.class);
		}
		catch (JacksonException ex) {
			throw new InvalidWebhookException("Webhook body is not valid JSON", ex);
		}
		if (webhook == null) {
			throw new InvalidWebhookException("Webhook body is empty");
		}
		try {
			return new ProviderWebhook(webhook.paymentId(),
					new ProviderAnswer(webhook.status(), webhook.reference(), webhook.reason()).toResult());
		}
		catch (IllegalArgumentException | IllegalStateException ex) {
			throw new InvalidWebhookException(ex.getMessage(), ex);
		}
	}

	static String sign(String secret, byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 is not available", ex);
		}
	}

	record ProviderAnswer(String status, String reference, String reason) {

		ProviderResult toResult() {
			if (status == null) {
				throw new IllegalStateException("Payment provider http answered without a status");
			}
			return switch (status) {
				case "APPROVED" -> ProviderResult.approved(reference);
				case "DECLINED" -> ProviderResult.declined(reason, reference);
				case "PENDING" -> ProviderResult.pending(reference);
				default -> throw new IllegalStateException(
						"Payment provider http answered unknown status '" + status + "'");
			};
		}

	}

	record WebhookBody(UUID paymentId, String status, String reference, String reason) {
	}

}
