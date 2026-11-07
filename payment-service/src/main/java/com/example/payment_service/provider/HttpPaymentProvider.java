package com.example.payment_service.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
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
import com.example.payment_service.entity.PendingAction;

@Component
@ConditionalOnProperty(name = "payment.provider.http.url")
public class HttpPaymentProvider implements PaymentProvider, WebhookProvider, RefundStatusCheckingProvider,
		RefundingProvider, AuthorizationStatusCheckingProvider {

	public static final String NAME = "http";

	static final String SIGNATURE_PREFIX = "sha256=";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RestClient restClient;

	private final String webhookSecret;

	private final URI statusUrl;

	private final URI refundUrl;

	private final URI authorizeUrl;

	private final URI captureUrl;

	private final URI voidUrl;

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
		this.authorizeUrl = properties.authorizeUrl();
		this.captureUrl = properties.captureUrl();
		this.voidUrl = properties.voidUrl();
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
	public boolean supportsManualCapture() {
		return authorizeUrl != null && captureUrl != null && voidUrl != null;
	}

	@Override
	public AuthorizationResult authorize(ProviderRequest request) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("paymentId", request.paymentId());
		body.put("method", request.method());
		body.put("currency", request.currency());
		body.put("amount", request.amount());
		return post(authorizeUrl, request.paymentId().toString(), body, "authorization").toAuthorization();
	}

	@Override
	public ProviderResult capture(CaptureRequest request) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("paymentId", request.paymentId());
		body.put("authorizationReference", request.authorizationReference());
		body.put("currency", request.currency());
		body.put("amount", request.amount());
		return post(captureUrl, request.paymentId() + ":capture", body, "capture").toResult();
	}

	@Override
	public ProviderResult voidAuthorization(VoidRequest request) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("paymentId", request.paymentId());
		body.put("authorizationReference", request.authorizationReference());
		return post(voidUrl, request.paymentId() + ":void", body, "void").toResult();
	}

	@Override
	public AuthorizationResult checkAuthorizationStatus(ProviderStatusRequest request) {
		return statusOf(request.paymentId(), request.providerReference(), "authorization").toAuthorization();
	}

	@Override
	public ProviderResult checkActionStatus(ActionStatusRequest request) {
		String type = (request.action() == PendingAction.CAPTURE) ? "capture" : "void";
		return statusOf(request.paymentId(), request.authorizationReference(), type).toResult();
	}

	private ProviderAnswer post(URI url, String idempotencyKey, Map<String, Object> body, String what) {
		if (!supportsManualCapture()) {
			throw new IllegalStateException("Payment provider http has no authorize, capture and void URLs");
		}
		ProviderAnswer answer = restClient.post()
			.uri(url)
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", idempotencyKey)
			.body(body)
			.retrieve()
			.body(ProviderAnswer.class);
		if (answer == null) {
			throw new IllegalStateException("Payment provider http answered the " + what + " without a status");
		}
		return answer;
	}

	private ProviderAnswer statusOf(UUID paymentId, String reference, String type) {
		if (!checksStatus()) {
			throw new IllegalStateException("Payment provider http has no status URL");
		}
		UriComponentsBuilder uri = UriComponentsBuilder.fromUri(statusUrl).queryParam("paymentId", paymentId);
		if (StringUtils.hasText(reference)) {
			uri.queryParam("reference", reference);
		}
		uri.queryParam("type", type);
		ProviderAnswer answer = restClient.get()
			.uri(uri.encode().build().toUri())
			.accept(MediaType.APPLICATION_JSON)
			.retrieve()
			.body(ProviderAnswer.class);
		if (answer == null) {
			throw new IllegalStateException("Payment provider http answered the " + type + " status query without a status");
		}
		return answer;
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
			ProviderResult result = new ProviderAnswer(webhook.status(), webhook.reference(), webhook.reason(), null)
				.toResult();
			if (webhook.type() == null) {
				return new ProviderWebhook(webhook.paymentId(), result);
			}
			return switch (webhook.type()) {
				case "payment" -> new ProviderWebhook(webhook.paymentId(), result);
				case "refund" -> ProviderWebhook.refund(webhook.refundId(), result);
				case "authorization" -> ProviderWebhook.authorization(webhook.paymentId(), result, webhook.expiresAt());
				case "capture" -> ProviderWebhook.capture(webhook.paymentId(), result);
				case "void" -> ProviderWebhook.voided(webhook.paymentId(), result);
				default -> throw new InvalidWebhookException("Unknown webhook type '" + webhook.type() + "'", null);
			};
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

	record ProviderAnswer(String status, String reference, String reason, Instant expiresAt) {

		AuthorizationResult toAuthorization() {
			ProviderResult result = toResult();
			return new AuthorizationResult(result, (result instanceof ProviderResult.Declined) ? null : expiresAt);
		}

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

	record WebhookBody(String type, UUID paymentId, UUID refundId, String status, String reference, String reason,
			Instant expiresAt) {
	}

}
