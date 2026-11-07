package com.example.payment_service.authorization;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.HttpPaymentProviderTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = { "payment.provider.routes.CARD=http", "payment.provider.http.read-timeout=1s",
		"payment.provider.http.webhook-secret=" + AuthorizationWebhookIntegrationTests.SECRET })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class AuthorizationWebhookIntegrationTests {

	static final String SECRET = "authorization-webhook-secret";

	private static final String DEMO_KEY = "demo-key-123";

	private static final String EXPIRES = "2099-01-22T10:30:00Z";

	private static final Map<String, String> answers = new ConcurrentHashMap<>();

	private static final HttpServer provider = startProvider();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ApplicationEvents events;

	@DynamicPropertySource
	static void providerUrls(DynamicPropertyRegistry registry) {
		String base = "http://localhost:" + provider.getAddress().getPort();
		registry.add("payment.provider.http.url", () -> base + "/pay");
		registry.add("payment.provider.http.authorize-url", () -> base + "/authorize");
		registry.add("payment.provider.http.capture-url", () -> base + "/capture");
		registry.add("payment.provider.http.void-url", () -> base + "/void");
	}

	@BeforeEach
	void approveEverything() {
		answers.put("/authorize", """
				{"status":"APPROVED","reference":"acme_a_%s","expiresAt":"%s"}""".formatted(UUID.randomUUID(), EXPIRES));
		answers.put("/capture", """
				{"status":"PENDING","reference":"acme_c_1"}""");
		answers.put("/void", """
				{"status":"PENDING","reference":"acme_v_1"}""");
	}

	@AfterAll
	static void stopProvider() {
		provider.stop(0);
	}

	@Test
	void aPendingAuthorizationIsAuthorizedOnceByItsWebhook() throws Exception {
		String reference = "acme_a_" + UUID.randomUUID();
		answers.put("/authorize", """
				{"status":"PENDING","reference":"%s"}""".formatted(reference));
		String payment = createManual("PENDING");

		webhook("""
				{"type":"authorization","status":"APPROVED","reference":"%s","expiresAt":"%s"}""".formatted(reference,
				EXPIRES)).andExpect(status().isNoContent());
		webhook("""
				{"type":"authorization","status":"DECLINED","reason":"Late","reference":"%s"}""".formatted(reference))
			.andExpect(status().isNoContent());

		mvc.perform(get(payment).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.status").value("AUTHORIZED"))
			.andExpect(jsonPath("$.authorizationExpiresAt").value(EXPIRES))
			.andExpect(jsonPath("$.declineReason").isEmpty());
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void anApprovedAuthorizationWebhookWithoutItsExpiryIsRejected() throws Exception {
		webhook("""
				{"type":"authorization","status":"APPROVED","reference":"acme_a_x"}""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_WEBHOOK"));
	}

	@Test
	void aPendingCaptureIsCapturedOnceByItsWebhook() throws Exception {
		String payment = createManual("AUTHORIZED");
		mvc.perform(post(payment + "/capture").header("X-API-Key", DEMO_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 30.00}")).andExpect(status().isAccepted());

		String capture = """
				{"type":"capture","paymentId":"%s","status":"APPROVED","reference":"acme_c_1"}""".formatted(id(payment));
		webhook(capture).andExpect(status().isNoContent());
		webhook(capture).andExpect(status().isNoContent());

		mvc.perform(get(payment).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.capturedAmount").value(30.00))
			.andExpect(jsonPath("$.pendingAction").isEmpty());
		assertThat(events.stream(PaymentProcessedEvent.class)).hasSize(1);
	}

	@Test
	void aPendingVoidIsVoidedOnceByItsWebhook() throws Exception {
		String payment = createManual("AUTHORIZED");
		mvc.perform(post(payment + "/void").header("X-API-Key", DEMO_KEY)).andExpect(status().isAccepted());

		String voided = """
				{"type":"void","paymentId":"%s","status":"APPROVED","reference":"acme_v_1"}""".formatted(id(payment));
		webhook(voided).andExpect(status().isNoContent());
		webhook(voided).andExpect(status().isNoContent());

		mvc.perform(get(payment).header("X-API-Key", DEMO_KEY)).andExpect(jsonPath("$.status").value("VOIDED"));
		assertThat(events.stream(PaymentProcessedEvent.class)).hasSize(1);
	}

	@Test
	void aDeclinedCaptureWebhookLeavesThePaymentAuthorized() throws Exception {
		String payment = createManual("AUTHORIZED");
		mvc.perform(post(payment + "/capture").header("X-API-Key", DEMO_KEY)).andExpect(status().isAccepted());

		webhook("""
				{"type":"capture","paymentId":"%s","status":"DECLINED","reason":"Cancelled","reference":"acme_c_1"}"""
			.formatted(id(payment))).andExpect(status().isNoContent());

		mvc.perform(get(payment).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.status").value("AUTHORIZED"))
			.andExpect(jsonPath("$.pendingAction").isEmpty());
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void aCaptureWebhookWithoutACaptureInProgressCapturesNothing() throws Exception {
		String payment = createManual("AUTHORIZED");

		webhook("""
				{"type":"capture","paymentId":"%s","status":"APPROVED","reference":"acme_c_9"}""".formatted(id(payment)))
			.andExpect(status().isNoContent());

		mvc.perform(get(payment).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.status").value("AUTHORIZED"))
			.andExpect(jsonPath("$.capturedAmount").isEmpty());
	}

	@Test
	void aCaptureWebhookForAnUnknownPaymentIsNotFoundSoTheProviderRetries() throws Exception {
		webhook("""
				{"type":"capture","paymentId":"%s","status":"APPROVED","reference":"acme_c_1"}""".formatted(UUID.randomUUID()))
			.andExpect(status().isNotFound());
	}

	@Test
	void aCaptureWebhookCannotTouchAnotherProvidersPayment() throws Exception {
		String created = mvc.perform(post("/api/v1/payments").header("X-API-Key", DEMO_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 10.00, \"currency\": \"EUR\", \"paymentMethod\": \"KVIK\", \"captureMethod\": \"MANUAL\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		webhook("""
				{"type":"capture","paymentId":"%s","status":"APPROVED","reference":"acme_c_1"}"""
			.formatted(JsonPath.read(created, "$.id").toString())).andExpect(status().isNotFound());
	}

	@Test
	void aCaptureWebhookWithoutAPaymentIdIsRejected() throws Exception {
		webhook("""
				{"type":"capture","status":"APPROVED","reference":"acme_c_1"}""").andExpect(status().isBadRequest());
	}

	private String createManual(String expectedStatus) throws Exception {
		String created = mvc.perform(post("/api/v1/payments").header("X-API-Key", DEMO_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 49.90, \"currency\": \"EUR\", \"paymentMethod\": \"CARD\", \"captureMethod\": \"MANUAL\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(expectedStatus))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return "/api/v1/payments/" + JsonPath.read(created, "$.id");
	}

	private static String id(String payment) {
		return payment.substring(payment.lastIndexOf('/') + 1);
	}

	private ResultActions webhook(String json) throws Exception {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		return mvc.perform(post("/api/v1/providers/http/webhook").contentType(MediaType.APPLICATION_JSON)
			.header("X-Webhook-Signature", HttpPaymentProviderTestSupport.sign(SECRET, body))
			.content(body));
	}

	private static HttpServer startProvider() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", (exchange) -> {
				exchange.getRequestBody().readAllBytes();
				String answer = answers.getOrDefault(exchange.getRequestURI().getPath(),
						"{\"status\":\"APPROVED\",\"reference\":\"acme_1\"}");
				byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, bytes.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
			});
			server.start();
			return server;
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

}
