package com.example.api_gateway;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayHealthTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void unreachableRedis(DynamicPropertyRegistry registry) throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		registry.add("REDIS_PORT", () -> closedPort);
	}

	@Test
	void gatewayIsUpWhenRedisIsDown() {
		WebTestClient.bindToServer()
			.baseUrl("http://localhost:" + port)
			.build()
			.get()
			.uri("/actuator/health")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status").isEqualTo("UP");
	}

}
