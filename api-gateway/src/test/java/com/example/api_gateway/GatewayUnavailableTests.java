package com.example.api_gateway;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayUnavailableTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void upstreamUrl(DynamicPropertyRegistry registry) throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:" + closedPort);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/payments", "/v3/api-docs" })
	void returnsJson503WhenPaymentServiceIsDown(String path) {
		WebTestClient.bindToServer()
			.baseUrl("http://localhost:" + port)
			.build()
			.get()
			.uri(path)
			.header("X-API-Key", "test-api-key")
			.exchange()
			.expectStatus()
			.isEqualTo(503)
			.expectHeader()
			.contentType(MediaType.APPLICATION_JSON)
			.expectBody()
			.jsonPath("$.timestamp").isNotEmpty()
			.jsonPath("$.status").isEqualTo(503)
			.jsonPath("$.error").isEqualTo("SERVICE_UNAVAILABLE")
			.jsonPath("$.message").isEqualTo("Payment service is unavailable")
			.jsonPath("$.path").isEqualTo(path)
			.jsonPath("$.requestId").doesNotExist();
	}

}
