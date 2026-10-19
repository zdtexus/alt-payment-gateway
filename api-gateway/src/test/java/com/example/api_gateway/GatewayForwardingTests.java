package com.example.api_gateway;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

}
