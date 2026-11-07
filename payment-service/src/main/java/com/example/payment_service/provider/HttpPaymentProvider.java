package com.example.payment_service.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
import org.springframework.web.util.UriComponentsBuilder;

import com.example.payment_service.error.InvalidWebhookException;
import com.example.payment_service.error.InvalidWebhookSignatureException;

@Component
@ConditionalOnProperty(name = "payment.provider.http.url")
public class HttpPaymentProvider
		implements PaymentProvider, WebhookProvider, RefundStatusCheckingProvider, RefundingProvider {

	public static final String NAME = "http";

	static final String SIGNATURE_PREFIX = "sha256=";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RestClient restClient;

	private final String webhookSecret;

	private final URI statusUrl;

	private final URI refundUrl;

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
		this.statusUrl = properties.statusUrl();
		this.refundUrl = properties.refundUrl();
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
	public boolean checksStatus() {
		return statusUrl != null;
	}

	@Override
	public ProviderResult checkStatus(ProviderStatusRequest request) {
		if (!checksStatus()) {
			throw new IllegalStateException("Payment provider http has no status URL");
		}
		UriComponentsBuilder uri = UriComponentsBuilder.fromUri(statusUrl).queryParam("paymentId", request.paymentId());
		if (StringUtils.hasText(request.providerReference())) {
			uri.queryParam("reference", request.providerReference());
		}
		ProviderAnswer answer = restClient.get()
			.uri(uri.encode().build().toUri())
			.accept(MediaType.APPLICATION_JSON)
			.retrieve()
			.body(ProviderAnswer.class);
		if (answer == null) {
			throw new IllegalStateException("Payment provider http answered the status query without a status");
		}
		return answer.toResult();
	}

	@Override
	public ProviderResult checkRefundStatus(RefundStatusRequest request) {
		if (!checksStatus()) {
			throw new IllegalStateException("Payment provider http has no status URL");
		}
		UriComponentsBuilder uri = UriComponentsBuilder.fromUri(statusUrl)
			.queryParam("refundId", request.refundId())
			.queryParam("paymentId", request.paymentId());
		if (StringUtils.hasText(request.providerReference())) {
			uri.queryParam("reference", request.providerReference());
		}
		ProviderAnswer answer = restClient.get()
			.uri(uri.encode().build().toUri())
			.accept(MediaType.APPLICATION_JSON)
			.retrieve()
			.body(ProviderAnswer.class);
		if (answer == null) {
			throw new IllegalStateException("Payment provider http answered the refund status query without a status");
		}
		return answer.toResult();
	}

	@Override
	public boolean supportsRefunds() {
		return refundUrl != null;
	}

	@Override
	public ProviderResult refund(RefundRequest request) {
		if (!supportsRefunds()) {
			throw new IllegalStateException("Payment provider http has no refund URL");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("refundId", request.refundId());
		body.put("paymentId", request.paymentId());
		body.put("paymentReference", request.paymentReference());
		body.put("currency", request.currency());
		body.put("amount", request.amount());
		ProviderAnswer answer = restClient.post()
			.uri(refundUrl)
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", request.refundId().toString())
			.body(body)
			.retrieve()
			.body(ProviderAnswer.class);
		if (answer == null) {
			throw new IllegalStateException("Payment provider http answered the refund without a status");
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
			ProviderResult result = new ProviderAnswer(webhook.status(), webhook.reference(), webhook.reason())
				.toResult();
			if (webhook.type() == null || "payment".equals(webhook.type())) {
				return new ProviderWebhook(webhook.paymentId(), result);
			}
			if ("refund".equals(webhook.type())) {
				return ProviderWebhook.refund(webhook.refundId(), result);
			}
			throw new InvalidWebhookException("Unknown webhook type '" + webhook.type() + "'", null);
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

	record WebhookBody(String type, UUID paymentId, UUID refundId, String status, String reference, String reason) {
	}

}
