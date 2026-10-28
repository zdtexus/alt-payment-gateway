package com.example.payment_service.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;
import com.example.payment_service.provider.HttpPaymentProviderTestSupport;
import com.example.payment_service.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = { "payment.provider.routes.CARD=http", "payment.provider.http.read-timeout=1s",
		"payment.provider.http.webhook-secret=" + ProviderWebhookIntegrationTests.SECRET })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProviderWebhookIntegrationTests {

	static final String SECRET = "webhook-secret-123456";

	private static final String DEMO_KEY = "demo-key-123";

	private static final HttpServer provider = startProvider();

	private static volatile String providerAnswer;

	private static volatile Duration providerDelay;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TransactionRepository transactionRepository;

	@MockitoBean
	private EmailSender emailSender;

	@MockitoBean
	private SmsSender smsSender;

	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	@DynamicPropertySource
	static void providerUrl(DynamicPropertyRegistry registry) {
		registry.add("payment.provider.http.url",
				() -> "http://localhost:" + provider.getAddress().getPort() + "/pay");
	}

	@AfterEach
	void reset() {
		executor.shutdownNow();
		transactionRepository.deleteAll();
		answer("""
				{"status":"PENDING","reference":"acme_1"}""", Duration.ZERO);
	}

	@AfterAll
	static void stopProvider() {
		provider.stop(0);
	}

	@Test
	void pendingPaymentSucceedsThroughSignedWebhookWithOneReceipt() throws Exception {
		String id = createPendingPayment("acme_1");

		webhook("http", """
				{"status":"APPROVED","reference":"acme_1"}""").andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/payments/" + id).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.processedAt").isNotEmpty());
		verify(emailSender, timeout(5000)).send(eq("shop@demo.local"), eq("Payment " + id + " succeeded"), any());
		verify(smsSender, timeout(5000)).send("+380501112233", "Payment 49.90 EUR succeeded");
	}

	@Test
	void pendingPaymentIsDeclinedThroughWebhookWithReason() throws Exception {
		String id = createPendingPayment("acme_1");

		webhook("http", """
				{"status":"DECLINED","reference":"acme_1","reason":"Card expired"}""")
			.andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/payments/" + id).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.status").value("DECLINED"))
			.andExpect(jsonPath("$.declineReason").value("Card expired"));
		verify(emailSender, timeout(5000)).send(any(), eq("Payment " + id + " declined"), contains("Card expired"));
	}

	@Test
	void repeatedOrContradictingWebhookChangesNothingAndSendsNoSecondReceipt() throws Exception {
		String id = createPendingPayment("acme_1");
		webhook("http", """
				{"status":"APPROVED","reference":"acme_1"}""").andExpect(status().isNoContent());

		webhook("http", """
				{"status":"APPROVED","reference":"acme_1"}""").andExpect(status().isNoContent());
		webhook("http", """
				{"status":"DECLINED","reference":"acme_1","reason":"Card expired"}""")
			.andExpect(status().isNoContent());

		Transaction stored = transactionRepository.findById(UUID.fromString(id)).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(stored.getDeclineReason()).isNull();
		verify(emailSender, after(1500).times(1)).send(any(), any(), any());
		verify(smsSender, times(1)).send(any(), any());
	}

	@Test
	void wrongOrMissingSignatureChangesNothing() throws Exception {
		String id = createPendingPayment("acme_1");
		byte[] body = """
				{"status":"APPROVED","reference":"acme_1"}""".getBytes(StandardCharsets.UTF_8);

		mvc.perform(post("/api/v1/providers/http/webhook").contentType(MediaType.APPLICATION_JSON)
			.header(ProviderWebhookController.SIGNATURE_HEADER, HttpPaymentProviderTestSupport.sign("wrong-secret-123456", body))
			.content(body))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("INVALID_WEBHOOK_SIGNATURE"))
			.andExpect(jsonPath("$.message").value("Missing or invalid webhook signature"))
			.andExpect(jsonPath("$.path").value("/api/v1/providers/http/webhook"));
		mvc.perform(post("/api/v1/providers/http/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());

		assertThat(transactionRepository.findById(UUID.fromString(id)).orElseThrow().getStatus())
			.isEqualTo(TransactionStatus.PENDING);
		verify(emailSender, after(1000).never()).send(any(), any(), any());
	}

	@Test
	void paymentLeftPendingByTimeoutIsMatchedByPaymentId() throws Exception {
		answer("""
				{"status":"DECLINED","reference":"acme_late","reason":"Too late"}""", Duration.ofSeconds(3));
		String id = createPayment("PENDING");
		assertThat(transactionRepository.findById(UUID.fromString(id)).orElseThrow().getProviderReference()).isNull();

		webhook("http", """
				{"paymentId":"%s","status":"APPROVED","reference":"acme_9"}""".formatted(id))
			.andExpect(status().isNoContent());

		Transaction stored = transactionRepository.findById(UUID.fromString(id)).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(stored.getProviderReference()).isEqualTo("acme_9");
	}

	@Test
	void webhookArrivingBeforeTheProviderAnswerWinsOnce() throws Exception {
		answer("""
				{"status":"DECLINED","reference":"acme_1","reason":"Late answer"}""", Duration.ofMillis(700));
		Future<String> payment = executor.submit(() -> createPayment("SUCCEEDED"));
		Transaction pending = awaitPendingPayment();

		webhook("http", """
				{"paymentId":"%s","status":"APPROVED","reference":"acme_1"}""".formatted(pending.getId()))
			.andExpect(status().isNoContent());

		assertThat(payment.get(10, TimeUnit.SECONDS)).isEqualTo(pending.getId().toString());
		Transaction stored = transactionRepository.findById(pending.getId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(stored.getDeclineReason()).isNull();
		verify(emailSender, after(1500).times(1)).send(any(), any(), any());
	}

	@Test
	void unknownReferenceIsNotFoundSoTheProviderRetries() throws Exception {
		webhook("http", """
				{"status":"APPROVED","reference":"acme_unknown"}""")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("No payment of provider 'http' matches reference 'acme_unknown'"));
	}

	@Test
	void providersWithoutWebhooksAreNotFound() throws Exception {
		for (String name : new String[] { "simulated", "stripe" }) {
			webhook(name, """
					{"status":"APPROVED","reference":"acme_1"}""")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("PROVIDER_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Payment provider '" + name + "' does not accept webhooks"));
		}
	}

	@Test
	void signedButInvalidWebhookIsBadRequest() throws Exception {
		webhook("http", """
				{"status":"APPROVED"}""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_WEBHOOK"));
		verify(emailSender, never()).send(any(), any(), any());
	}

	private String createPendingPayment(String reference) throws Exception {
		String id = createPayment("PENDING");
		assertThat(transactionRepository.findById(UUID.fromString(id)).orElseThrow().getProviderReference())
			.isEqualTo(reference);
		return id;
	}

	private String createPayment(String expectedStatus) throws Exception {
		String response = mvc
			.perform(post("/api/v1/payments").header("X-API-Key", DEMO_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount": 49.90, "currency": "EUR", "paymentMethod": "CARD"}"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(expectedStatus))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(response, "$.id");
	}

	private Transaction awaitPendingPayment() throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			var payments = transactionRepository.findAll();
			if (!payments.isEmpty()) {
				return payments.getFirst();
			}
			Thread.sleep(20);
		}
		throw new AssertionError("No payment was created");
	}

	private ResultActions webhook(String name, String json) throws Exception {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		return mvc.perform(post("/api/v1/providers/" + name + "/webhook").contentType(MediaType.APPLICATION_JSON)
			.header(ProviderWebhookController.SIGNATURE_HEADER, HttpPaymentProviderTestSupport.sign(SECRET, body))
			.content(body));
	}

	private static void answer(String body, Duration delay) {
		providerAnswer = body;
		providerDelay = delay;
	}

	private static HttpServer startProvider() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/pay", (exchange) -> {
				exchange.getRequestBody().readAllBytes();
				try {
					Thread.sleep(providerDelay);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				byte[] bytes = providerAnswer.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				try (OutputStream out = exchange.getResponseBody()) {
					exchange.sendResponseHeaders(200, bytes.length);
					out.write(bytes);
				}
				catch (IOException ex) {
					exchange.close();
				}
			});
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			server.start();
			answer("""
					{"status":"PENDING","reference":"acme_1"}""", Duration.ZERO);
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
