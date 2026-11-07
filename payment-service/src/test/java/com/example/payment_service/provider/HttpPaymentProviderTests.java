package com.example.payment_service.provider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.error.InvalidWebhookException;
import com.example.payment_service.error.InvalidWebhookSignatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HttpPaymentProviderTests {

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	private static final String WEBHOOK_SECRET = "webhook-secret-123456";

	private static final ProviderRequest REQUEST = new ProviderRequest(PAYMENT_ID, PaymentMethod.CARD, "EUR",
			new BigDecimal("49.90"));

	private HttpServer server;

	private final AtomicReference<String> method = new AtomicReference<>();

	private final AtomicReference<String> authorization = new AtomicReference<>();

	private final AtomicReference<String> idempotencyKey = new AtomicReference<>();

	private final AtomicReference<String> contentType = new AtomicReference<>();

	private final AtomicReference<String> contentLength = new AtomicReference<>();

	private final AtomicReference<String> body = new AtomicReference<>();

	private final AtomicReference<String> statusPath = new AtomicReference<>();

	private final AtomicReference<String> statusQuery = new AtomicReference<>();

	private final AtomicReference<String> statusAccept = new AtomicReference<>();

	private volatile int responseStatus = 200;

	private volatile String responseBody = """
			{"status":"APPROVED","reference":"acme_1"}""";

	private volatile Duration responseDelay = Duration.ZERO;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/pay", (exchange) -> {
			method.set(exchange.getRequestMethod());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			try {
				Thread.sleep(responseDelay);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(responseStatus, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.createContext("/refund", (exchange) -> {
			method.set(exchange.getRequestMethod());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(responseStatus, (bytes.length == 0) ? -1 : bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.createContext("/status", (exchange) -> {
			method.set(exchange.getRequestMethod());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			statusPath.set(exchange.getRequestURI().getRawPath());
			statusQuery.set(exchange.getRequestURI().getRawQuery());
			statusAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
			try {
				Thread.sleep(responseDelay);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(responseStatus, (bytes.length == 0) ? -1 : bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void postsPaymentAsJsonWithBearerKeyAndPaymentIdAsIdempotencyKey() {
		provider("secret", Duration.ofSeconds(5)).process(REQUEST);

		assertThat(method).hasValue("POST");
		assertThat(authorization).hasValue("Bearer secret");
		assertThat(idempotencyKey).hasValue(PAYMENT_ID.toString());
		assertThat(contentType.get()).startsWith("application/json");
		assertThat(body.get()).contains("\"paymentId\":\"" + PAYMENT_ID + "\"")
			.contains("\"method\":\"CARD\"")
			.contains("\"currency\":\"EUR\"")
			.contains("\"amount\":49.90");
	}

	@Test
	void sendsBodyWithContentLengthInsteadOfChunked() {
		provider("secret", Duration.ofSeconds(5)).process(REQUEST);

		assertThat(contentLength.get()).isNotNull()
			.isEqualTo(String.valueOf(body.get().getBytes(StandardCharsets.UTF_8).length));
	}

	@Test
	void omitsAuthorizationWithoutApiKey() {
		provider(null, Duration.ofSeconds(5)).process(REQUEST);

		assertThat(authorization).hasValue(null);
	}

	@Test
	void approvedAnswerIsApproved() {
		assertThat(provider("secret", Duration.ofSeconds(5)).process(REQUEST))
			.isEqualTo(ProviderResult.approved("acme_1"));
	}

	@Test
	void declinedAnswerIsDeclinedWithReason() {
		responseBody = """
				{"status":"DECLINED","reference":"acme_2","reason":"Card expired"}""";

		assertThat(provider("secret", Duration.ofSeconds(5)).process(REQUEST))
			.isEqualTo(ProviderResult.declined("Card expired", "acme_2"));
	}

	@Test
	void pendingAnswerIsPending() {
		responseBody = """
				{"status":"PENDING","reference":"acme_3"}""";

		assertThat(provider("secret", Duration.ofSeconds(5)).process(REQUEST))
			.isEqualTo(ProviderResult.pending("acme_3"));
	}

	@Test
	void unknownStatusIsThrown() {
		responseBody = """
				{"status":"MAYBE"}""";

		assertThatIllegalStateException().isThrownBy(() -> provider("secret", Duration.ofSeconds(5)).process(REQUEST))
			.withMessage("Payment provider http answered unknown status 'MAYBE'");
	}

	@Test
	void missingStatusIsThrown() {
		responseBody = "{}";

		assertThatIllegalStateException().isThrownBy(() -> provider("secret", Duration.ofSeconds(5)).process(REQUEST))
			.withMessage("Payment provider http answered without a status");
	}

	@Test
	void declineWithoutReasonIsThrown() {
		responseBody = """
				{"status":"DECLINED","reference":"acme_2"}""";

		assertThatIllegalArgumentException()
			.isThrownBy(() -> provider("secret", Duration.ofSeconds(5)).process(REQUEST));
	}

	@Test
	void serverErrorIsThrown() {
		responseStatus = 500;

		assertThatExceptionOfType(HttpServerErrorException.class)
			.isThrownBy(() -> provider("secret", Duration.ofSeconds(5)).process(REQUEST));
	}

	@Test
	void slowServerTimesOutAfterReadTimeout() {
		responseDelay = Duration.ofSeconds(3);

		long start = System.nanoTime();
		assertThatExceptionOfType(ResourceAccessException.class)
			.isThrownBy(() -> provider("secret", Duration.ofMillis(200)).process(REQUEST));

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void unreachableServerIsThrown() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		HttpPaymentProvider provider = new HttpPaymentProvider(new HttpPaymentProviderProperties(
				URI.create("http://localhost:" + closedPort + "/pay"), null, Duration.ofSeconds(1),
				Duration.ofSeconds(1), null, null, null));

		assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(() -> provider.process(REQUEST));
	}

	@Test
	void usesConfiguredConnectTimeout() {
		HttpPaymentProviderProperties properties = new HttpPaymentProviderProperties(URI.create("http://localhost"),
				null, Duration.ofMillis(750), Duration.ofSeconds(10), null, null, null);

		assertThat(HttpPaymentProvider.httpClient(properties).connectTimeout()).hasValue(Duration.ofMillis(750));
	}

	@Test
	void isNamedHttp() {
		assertThat(provider(null, Duration.ofSeconds(5)).name()).isEqualTo("http");
	}

	@Test
	void acceptsWebhooksOnlyWithSecret() {
		assertThat(provider(null, Duration.ofSeconds(5)).acceptsWebhooks()).isFalse();
		assertThat(webhookProvider().acceptsWebhooks()).isTrue();
	}

	@Test
	void readsSignedWebhook() {
		byte[] body = webhookBody("""
				{"paymentId":"%s","status":"DECLINED","reference":"acme_1","reason":"Card expired"}""");

		ProviderWebhook webhook = webhookProvider().readWebhook(body, HttpPaymentProvider.sign(WEBHOOK_SECRET, body));

		assertThat(webhook)
			.isEqualTo(new ProviderWebhook(PAYMENT_ID, ProviderResult.declined("Card expired", "acme_1")));
	}

	@Test
	void readsWebhookWithoutPaymentId() {
		byte[] body = webhookBody("""
				{"status":"APPROVED","reference":"acme_1"}""");

		assertThat(webhookProvider().readWebhook(body, HttpPaymentProvider.sign(WEBHOOK_SECRET, body)))
			.isEqualTo(new ProviderWebhook(null, ProviderResult.approved("acme_1")));
	}

	@Test
	void signatureIsHexHmacSha256OfTheRawBody() {
		byte[] body = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);

		assertThat(HttpPaymentProvider.sign("key", body))
			.isEqualTo("sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "sha256=00", "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8" })
	void rejectsMissingOrWrongSignature(String signature) {
		byte[] body = webhookBody("""
				{"paymentId":"%s","status":"APPROVED","reference":"acme_1"}""");

		assertThatExceptionOfType(InvalidWebhookSignatureException.class)
			.isThrownBy(() -> webhookProvider().readWebhook(body, signature));
	}

	@Test
	void rejectsSignatureOfAnotherBody() {
		byte[] signed = webhookBody("""
				{"paymentId":"%s","status":"DECLINED","reference":"acme_1","reason":"Card expired"}""");
		byte[] sent = webhookBody("""
				{"paymentId":"%s","status":"APPROVED","reference":"acme_1"}""");

		assertThatExceptionOfType(InvalidWebhookSignatureException.class)
			.isThrownBy(() -> webhookProvider().readWebhook(sent, HttpPaymentProvider.sign(WEBHOOK_SECRET, signed)));
	}

	@Test
	void rejectsSignatureMadeWithAnotherSecret() {
		byte[] body = webhookBody("""
				{"paymentId":"%s","status":"APPROVED","reference":"acme_1"}""");

		assertThatExceptionOfType(InvalidWebhookSignatureException.class).isThrownBy(
				() -> webhookProvider().readWebhook(body, HttpPaymentProvider.sign("another-secret-1234567", body)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "not json", "{}", "{\"status\":\"MAYBE\",\"reference\":\"acme_1\"}",
			"{\"status\":\"APPROVED\"}", "{\"status\":\"DECLINED\",\"reference\":\"acme_1\"}",
			"{\"status\":\"APPROVED\",\"reference\":\"acme_1\",\"paymentId\":\"not-a-uuid\"}" })
	void rejectsSignedButInvalidWebhook(String json) {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);

		assertThatExceptionOfType(InvalidWebhookException.class)
			.isThrownBy(() -> webhookProvider().readWebhook(body, HttpPaymentProvider.sign(WEBHOOK_SECRET, body)));
	}

	@Test
	void statusQueryIsAGetWithPaymentIdReferenceAndBearerKey() {
		statusProvider("secret", Duration.ofSeconds(5))
			.checkStatus(new ProviderStatusRequest(PAYMENT_ID, "acme_1"));

		assertThat(method).hasValue("GET");
		assertThat(statusPath).hasValue("/status");
		assertThat(statusQuery).hasValue("paymentId=" + PAYMENT_ID + "&reference=acme_1");
		assertThat(authorization).hasValue("Bearer secret");
		assertThat(statusAccept.get()).contains("application/json");
	}

	@Test
	void statusQueryWithoutReferenceSendsOnlyThePaymentId() {
		statusProvider(null, Duration.ofSeconds(5)).checkStatus(new ProviderStatusRequest(PAYMENT_ID, null));

		assertThat(statusQuery).hasValue("paymentId=" + PAYMENT_ID);
		assertThat(authorization).hasValue(null);
	}

	@Test
	void statusQueryEncodesTheReferenceAndKeepsTheUrlsOwnQuery() {
		URI url = URI.create("http://localhost:" + server.getAddress().getPort() + "/pay");
		URI status = URI.create("http://localhost:" + server.getAddress().getPort() + "/status?merchant=demo");
		new HttpPaymentProvider(new HttpPaymentProviderProperties(url, null, Duration.ofSeconds(2),
				Duration.ofSeconds(5), null, status, null))
			.checkStatus(new ProviderStatusRequest(PAYMENT_ID, "ref with space&more"));

		assertThat(statusQuery).hasValue("merchant=demo&paymentId=" + PAYMENT_ID + "&reference=ref%20with%20space%26more");
	}

	@Test
	void statusAnswersUseThePaymentAnswerFormat() {
		HttpPaymentProvider provider = statusProvider("secret", Duration.ofSeconds(5));
		ProviderStatusRequest request = new ProviderStatusRequest(PAYMENT_ID, "acme_1");

		assertThat(provider.checkStatus(request)).isEqualTo(ProviderResult.approved("acme_1"));
		responseBody = """
				{"status":"DECLINED","reference":"acme_1","reason":"Card expired"}""";
		assertThat(provider.checkStatus(request)).isEqualTo(ProviderResult.declined("Card expired", "acme_1"));
		responseBody = """
				{"status":"PENDING","reference":"acme_1"}""";
		assertThat(provider.checkStatus(request)).isEqualTo(ProviderResult.pending("acme_1"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"status\":\"MAYBE\"}", "{}", "{\"status\":\"DECLINED\"}", "" })
	void untrustedStatusAnswerIsThrown(String answer) {
		responseBody = answer;

		assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> statusProvider("secret", Duration.ofSeconds(5))
			.checkStatus(new ProviderStatusRequest(PAYMENT_ID, "acme_1")));
	}

	@ParameterizedTest
	@ValueSource(ints = { 404, 500, 503 })
	void statusErrorIsThrown(int status) {
		responseStatus = status;

		assertThatExceptionOfType(RestClientResponseException.class).isThrownBy(() -> statusProvider("secret",
				Duration.ofSeconds(5)).checkStatus(new ProviderStatusRequest(PAYMENT_ID, "acme_1")));
	}

	@Test
	void slowStatusAnswerTimesOutAfterReadTimeout() {
		responseDelay = Duration.ofSeconds(3);

		long start = System.nanoTime();
		assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(() -> statusProvider("secret",
				Duration.ofMillis(200)).checkStatus(new ProviderStatusRequest(PAYMENT_ID, "acme_1")));

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void withoutStatusUrlTheProviderDoesNotCheckStatus() {
		HttpPaymentProvider provider = provider("secret", Duration.ofSeconds(5));

		assertThat(provider.checksStatus()).isFalse();
		assertThatIllegalStateException()
			.isThrownBy(() -> provider.checkStatus(new ProviderStatusRequest(PAYMENT_ID, "acme_1")))
			.withMessage("Payment provider http has no status URL");
		assertThat(statusPath).hasValue(null);
		assertThat(statusProvider("secret", Duration.ofSeconds(5)).checksStatus()).isTrue();
	}

	@Test
	void statusRequestNeedsAPaymentId() {
		assertThatIllegalArgumentException().isThrownBy(() -> new ProviderStatusRequest(null, "acme_1"))
			.withMessage("paymentId is required");
	}

	private static final UUID REFUND_ID = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

	private static final RefundRequest REFUND = new RefundRequest(REFUND_ID, PAYMENT_ID, "acme_1", "EUR",
			new BigDecimal("20.00"));

	@Test
	void refundIsAPostWithTheRefundIdAsIdempotencyKey() {
		refundProvider("secret").refund(REFUND);

		assertThat(method).hasValue("POST");
		assertThat(authorization).hasValue("Bearer secret");
		assertThat(idempotencyKey).hasValue(REFUND_ID.toString());
		assertThat(body.get()).contains("\"refundId\":\"" + REFUND_ID + "\"")
			.contains("\"paymentId\":\"" + PAYMENT_ID + "\"")
			.contains("\"paymentReference\":\"acme_1\"")
			.contains("\"currency\":\"EUR\"")
			.contains("\"amount\":20.00");
	}

	@Test
	void refundAnswersUseThePaymentAnswerFormat() {
		HttpPaymentProvider provider = refundProvider("secret");

		responseBody = """
				{"status":"APPROVED","reference":"acme_r_1"}""";
		assertThat(provider.refund(REFUND)).isEqualTo(ProviderResult.approved("acme_r_1"));
		responseBody = """
				{"status":"DECLINED","reference":"acme_r_2","reason":"Card account closed"}""";
		assertThat(provider.refund(REFUND)).isEqualTo(ProviderResult.declined("Card account closed", "acme_r_2"));
		responseBody = """
				{"status":"PENDING","reference":"acme_r_3"}""";
		assertThat(provider.refund(REFUND)).isEqualTo(ProviderResult.pending("acme_r_3"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"status\":\"MAYBE\"}", "{}", "" })
	void untrustedRefundAnswerIsThrown(String answer) {
		responseBody = answer;

		assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> refundProvider("secret").refund(REFUND));
	}

	@Test
	void refundErrorStatusIsThrown() {
		responseStatus = 500;

		assertThatExceptionOfType(HttpServerErrorException.class)
			.isThrownBy(() -> refundProvider("secret").refund(REFUND));
	}

	@Test
	void withoutRefundUrlTheProviderDoesNotRefund() {
		HttpPaymentProvider provider = provider("secret", Duration.ofSeconds(5));

		assertThat(provider.supportsRefunds()).isFalse();
		assertThatIllegalStateException().isThrownBy(() -> provider.refund(REFUND))
			.withMessage("Payment provider http has no refund URL");
		assertThat(method).hasValue(null);
		assertThat(refundProvider("secret").supportsRefunds()).isTrue();
	}

	@Test
	void refundStatusQueryNamesTheRefundAndThePayment() {
		statusProvider("secret", Duration.ofSeconds(5))
			.checkRefundStatus(new RefundStatusRequest(REFUND_ID, PAYMENT_ID, "acme_r_1"));

		assertThat(method).hasValue("GET");
		assertThat(statusQuery).hasValue("refundId=" + REFUND_ID + "&paymentId=" + PAYMENT_ID + "&reference=acme_r_1");
		assertThat(authorization).hasValue("Bearer secret");
	}

	@Test
	void refundStatusQueryWithoutReferenceAndWithoutStatusUrl() {
		statusProvider(null, Duration.ofSeconds(5)).checkRefundStatus(new RefundStatusRequest(REFUND_ID, PAYMENT_ID, null));

		assertThat(statusQuery).hasValue("refundId=" + REFUND_ID + "&paymentId=" + PAYMENT_ID);
		assertThatIllegalStateException().isThrownBy(() -> provider("secret", Duration.ofSeconds(5))
			.checkRefundStatus(new RefundStatusRequest(REFUND_ID, PAYMENT_ID, null)));
	}

	@Test
	void readsSignedRefundWebhook() {
		byte[] body = ("{\"type\":\"refund\",\"refundId\":\"" + REFUND_ID
				+ "\",\"status\":\"APPROVED\",\"reference\":\"acme_r_1\"}").getBytes(StandardCharsets.UTF_8);

		ProviderWebhook webhook = webhookProvider().readWebhook(body, HttpPaymentProvider.sign(WEBHOOK_SECRET, body));

		assertThat(webhook).isEqualTo(ProviderWebhook.refund(REFUND_ID, ProviderResult.approved("acme_r_1")));
		assertThat(webhook.type()).isEqualTo(ProviderWebhook.Type.REFUND);
	}

	@Test
	void aWebhookOfTypePaymentIsAPaymentWebhook() {
		byte[] body = webhookBody("""
				{"type":"payment","paymentId":"%s","status":"APPROVED","reference":"acme_1"}""");

		assertThat(webhookProvider().readWebhook(body, HttpPaymentProvider.sign(WEBHOOK_SECRET, body)))
			.isEqualTo(new ProviderWebhook(PAYMENT_ID, ProviderResult.approved("acme_1")));
	}

	@Test
	void anUnknownWebhookTypeIsInvalid() {
		byte[] body = webhookBody("""
				{"type":"chargeback","paymentId":"%s","status":"APPROVED","reference":"acme_1"}""");

		assertThatExceptionOfType(InvalidWebhookException.class)
			.isThrownBy(() -> webhookProvider().readWebhook(body, HttpPaymentProvider.sign(WEBHOOK_SECRET, body)))
			.withMessage("Unknown webhook type 'chargeback'");
	}

	private HttpPaymentProvider refundProvider(String apiKey) {
		URI url = URI.create("http://localhost:" + server.getAddress().getPort() + "/pay");
		URI refund = URI.create("http://localhost:" + server.getAddress().getPort() + "/refund");
		return new HttpPaymentProvider(new HttpPaymentProviderProperties(url, apiKey, Duration.ofSeconds(2),
				Duration.ofSeconds(5), null, null, refund));
	}

	private HttpPaymentProvider statusProvider(String apiKey, Duration readTimeout) {
		URI url = URI.create("http://localhost:" + server.getAddress().getPort() + "/pay");
		URI status = URI.create("http://localhost:" + server.getAddress().getPort() + "/status");
		return new HttpPaymentProvider(
				new HttpPaymentProviderProperties(url, apiKey, Duration.ofSeconds(2), readTimeout, null, status, null));
	}

	private HttpPaymentProvider webhookProvider() {
		URI url = URI.create("http://localhost:" + server.getAddress().getPort() + "/pay");
		return new HttpPaymentProvider(new HttpPaymentProviderProperties(url, null, Duration.ofSeconds(2),
				Duration.ofSeconds(5), WEBHOOK_SECRET, null, null));
	}

	private static byte[] webhookBody(String template) {
		return template.formatted(PAYMENT_ID).getBytes(StandardCharsets.UTF_8);
	}

	private HttpPaymentProvider provider(String apiKey, Duration readTimeout) {
		URI url = URI.create("http://localhost:" + server.getAddress().getPort() + "/pay");
		return new HttpPaymentProvider(
				new HttpPaymentProviderProperties(url, apiKey, Duration.ofSeconds(2), readTimeout, null, null, null));
	}

}
