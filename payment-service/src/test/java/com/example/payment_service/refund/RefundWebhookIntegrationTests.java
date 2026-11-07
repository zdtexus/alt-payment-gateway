package com.example.payment_service.refund;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.provider.HttpPaymentProviderTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = { "payment.provider.routes.CARD=http", "payment.provider.http.read-timeout=1s",
		"payment.provider.http.webhook-secret=" + RefundWebhookIntegrationTests.SECRET })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RefundWebhookIntegrationTests {

	static final String SECRET = "refund-webhook-secret-42";

	private static final String DEMO_KEY = "demo-key-123";

	private static final HttpServer provider = startProvider();

	private static volatile String refundAnswer;

	private static volatile Duration refundDelay = Duration.ZERO;

	@Autowired
	private MockMvc mvc;

	private String payment;

	@DynamicPropertySource
	static void providerUrls(DynamicPropertyRegistry registry) {
		String base = "http://localhost:" + provider.getAddress().getPort();
		registry.add("payment.provider.http.url", () -> base + "/pay");
		registry.add("payment.provider.http.refund-url", () -> base + "/refund");
	}

	@BeforeEach
	void succeededPayment() throws Exception {
		refundAnswer = """
				{"status":"PENDING","reference":"acme_r_1"}""";
		refundDelay = Duration.ZERO;
		String created = mvc.perform(post("/api/v1/payments").header("X-API-Key", DEMO_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 50.00, \"currency\": \"EUR\", \"paymentMethod\": \"CARD\"}"))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		payment = "/api/v1/payments/" + JsonPath.read(created, "$.id");
	}

	@AfterAll
	static void stopProvider() {
		provider.stop(0);
	}

	@Test
	void aPendingRefundSucceedsOnceThroughItsSignedWebhook() throws Exception {
		refundAnswer = """
				{"status":"PENDING","reference":"acme_r_%s"}""".formatted(UUID.randomUUID());
		String refund = createRefund("20.00");
		String reference = JsonPath.read(refundAnswer, "$.reference");

		webhook("""
				{"type":"refund","status":"APPROVED","reference":"%s"}""".formatted(reference))
			.andExpect(status().isNoContent());
		webhook("""
				{"type":"refund","status":"DECLINED","reason":"Late","reference":"%s"}""".formatted(reference))
			.andExpect(status().isNoContent());

		mvc.perform(get(payment + "/refunds/" + refund).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.declineReason").doesNotExist());
		mvc.perform(get(payment).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.refundedAmount").value(20.00))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"));
	}

	@Test
	void aTimedOutRefundIsFoundByItsIdAndGetsItsReference() throws Exception {
		refundDelay = Duration.ofSeconds(2);
		String refund = createRefund("5.00");
		String reference = "acme_r_" + UUID.randomUUID();

		webhook("""
				{"type":"refund","refundId":"%s","status":"DECLINED","reason":"Card account closed","reference":"%s"}"""
			.formatted(refund, reference)).andExpect(status().isNoContent());

		mvc.perform(get(payment + "/refunds/" + refund).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.status").value("DECLINED"))
			.andExpect(jsonPath("$.declineReason").value("Card account closed"));
		mvc.perform(get(payment).header("X-API-Key", DEMO_KEY)).andExpect(jsonPath("$.refundedAmount").value(0.00));
	}

	@Test
	void aRefundWebhookForAnUnknownReferenceIsNotFoundSoTheProviderRetries() throws Exception {
		webhook("""
				{"type":"refund","status":"APPROVED","reference":"acme_r_unknown"}""")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("REFUND_NOT_FOUND"));
	}

	@Test
	void anUnsignedRefundWebhookIsRejected() throws Exception {
		mvc.perform(post("/api/v1/providers/http/webhook").contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"refund\",\"status\":\"APPROVED\",\"reference\":\"acme_r_1\"}"))
			.andExpect(status().isUnauthorized());
	}

	private String createRefund(String amount) throws Exception {
		String created = mvc.perform(post(payment + "/refunds").header("X-API-Key", DEMO_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": " + amount + "}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(created, "$.id");
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
			server.createContext("/pay", (exchange) -> answer(exchange, "{\"status\":\"APPROVED\",\"reference\":\"acme_p_"
					+ UUID.randomUUID() + "\"}", Duration.ZERO));
			server.createContext("/refund", (exchange) -> answer(exchange, refundAnswer, refundDelay));
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void answer(com.sun.net.httpserver.HttpExchange exchange, String json, Duration delay)
			throws IOException {
		exchange.getRequestBody().readAllBytes();
		try {
			Thread.sleep(delay);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		try {
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		catch (IOException ex) {
			exchange.close();
		}
	}

}
