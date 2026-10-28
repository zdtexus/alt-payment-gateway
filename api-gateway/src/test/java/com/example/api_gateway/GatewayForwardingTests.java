package com.example.api_gateway;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayForwardingTests {

	private static final String API_KEY = "test-api-key";

	private static MockWebServer upstream;

	@LocalServerPort
	private int port;

	@BeforeAll
	static void startUpstream() throws IOException {
		upstream = new MockWebServer();
		upstream.start();
	}

	@AfterAll
	static void stopUpstream() throws IOException {
		upstream.shutdown();
	}

	@DynamicPropertySource
	static void upstreamUrl(DynamicPropertyRegistry registry) {
		registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:" + upstream.getPort());
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/payments", "/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html" })
	void forwardsApiKeyHeaderToPaymentService(String path) throws InterruptedException {
		upstream.enqueue(new MockResponse().setResponseCode(200));

		client().get().uri(path).header("X-API-Key", API_KEY).exchange().expectStatus().isOk();

		RecordedRequest request = upstream.takeRequest(5, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getPath()).isEqualTo(path);
		assertThat(request.getHeader("X-API-Key")).isEqualTo(API_KEY);
	}

	@Test
	void forwardsAdminKeyHeaderToPaymentService() throws InterruptedException {
		upstream.enqueue(new MockResponse().setResponseCode(201));

		client().post()
			.uri("/admin/v1/merchants")
			.header("X-Admin-Key", "test-admin-key")
			.exchange()
			.expectStatus()
			.isCreated();

		RecordedRequest request = upstream.takeRequest(5, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/admin/v1/merchants");
		assertThat(request.getHeader("X-Admin-Key")).isEqualTo("test-admin-key");
	}

	@ParameterizedTest
	@CsvSource({ "/api/v1/payments, 201", "/api/v1/payments/42, 401", "/api/v1/payments/42, 403",
			"/api/v1/payments/42, 404", "/api/v1/payments, 422", "/api/v1/payments, 500", "/v3/api-docs, 404" })
	void returnsUpstreamStatusAndBodyUnchanged(String path, int status) throws InterruptedException {
		String body = "{\"status\":" + status + "}";
		upstream.enqueue(new MockResponse().setResponseCode(status)
			.setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.setBody(body));

		client().get()
			.uri(path)
			.header("X-API-Key", API_KEY)
			.exchange()
			.expectStatus()
			.isEqualTo(status)
			.expectHeader()
			.contentType(MediaType.APPLICATION_JSON)
			.expectBody(String.class)
			.isEqualTo(body);

		upstream.takeRequest(5, TimeUnit.SECONDS);
	}

	@Test
	void forwardsPostBodyAndQueryAndReturnsUpstreamHeaders() throws InterruptedException {
		String requestBody = "{\"amount\":\"10.00\",\"currency\":\"EUR\",\"method\":\"CARD\"}";
		upstream.enqueue(new MockResponse().setResponseCode(201)
			.setHeader("Location", "/api/v1/payments/42")
			.setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.setBody("{\"id\":42}"));

		client().post()
			.uri("/api/v1/payments?source=web")
			.header("X-API-Key", API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(requestBody)
			.exchange()
			.expectStatus()
			.isCreated()
			.expectHeader()
			.valueEquals("Location", "/api/v1/payments/42")
			.expectBody(String.class)
			.isEqualTo("{\"id\":42}");

		RecordedRequest request = upstream.takeRequest(5, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/api/v1/payments?source=web");
		assertThat(request.getHeader("Content-Type")).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(request.getBody().readUtf8()).isEqualTo(requestBody);
	}

	@Test
	void doesNotForwardUnroutedPaths() throws InterruptedException {
		int requestsBefore = upstream.getRequestCount();

		client().get().uri("/api/v2/payments").exchange().expectStatus().isNotFound();

		assertThat(upstream.getRequestCount()).isEqualTo(requestsBefore);
	}

	@Test
	void listsAllRoutesInActuator() {
		client().get()
			.uri("/actuator/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$[*].route_id")
			.value(containsInAnyOrder("payment-service", "payment-service-admin", "payment-service-docs"));
	}

	@Test
	void returnsJson502WhenPaymentServiceDropsConnection() throws InterruptedException {
		upstream.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

		client().get()
			.uri("/api/v1/payments")
			.header("X-API-Key", API_KEY)
			.exchange()
			.expectStatus()
			.isEqualTo(502)
			.expectHeader()
			.contentType(MediaType.APPLICATION_JSON)
			.expectBody()
			.jsonPath("$.timestamp").isNotEmpty()
			.jsonPath("$.status").isEqualTo(502)
			.jsonPath("$.error").isEqualTo("BAD_GATEWAY")
			.jsonPath("$.message").isEqualTo("Payment service returned an invalid response")
			.jsonPath("$.path").isEqualTo("/api/v1/payments");

		upstream.takeRequest(5, TimeUnit.SECONDS);
	}

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

}
