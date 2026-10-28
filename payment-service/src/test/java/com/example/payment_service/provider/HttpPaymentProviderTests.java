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
				Duration.ofSeconds(1), null));

		assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(() -> provider.process(REQUEST));
	}

	@Test
	void usesConfiguredConnectTimeout() {
		HttpPaymentProviderProperties properties = new HttpPaymentProviderProperties(URI.create("http://localhost"),
				null, Duration.ofMillis(750), Duration.ofSeconds(10), null);

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

	private HttpPaymentProvider webhookProvider() {
		URI url = URI.create("http://localhost:" + server.getAddress().getPort() + "/pay");
		return new HttpPaymentProvider(new HttpPaymentProviderProperties(url, null, Duration.ofSeconds(2),
				Duration.ofSeconds(5), WEBHOOK_SECRET));
	}

	private static byte[] webhookBody(String template) {
		return template.formatted(PAYMENT_ID).getBytes(StandardCharsets.UTF_8);
	}

	private HttpPaymentProvider provider(String apiKey, Duration readTimeout) {
		URI url = URI.create("http://localhost:" + server.getAddress().getPort() + "/pay");
		return new HttpPaymentProvider(
				new HttpPaymentProviderProperties(url, apiKey, Duration.ofSeconds(2), readTimeout, null));
	}

}
