package com.example.api_gateway.redis;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

abstract class GatewayAgainstRedisTestSupport {

	static final int BURST = 3;

	static final String USERNAME = "gateway";

	static final String PASSWORD = "redis-test-secret-7Qx";

	static final GenericContainer<?> AUTH_REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(6379)
		.withCommand("redis-server", "--user", USERNAME, "on", ">" + PASSWORD, "~*", "&*", "+@all", "--user",
				"default", "off");

	static final GenericContainer<?> PLAIN_REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(6379);

	private static MockWebServer upstream;

	@LocalServerPort
	private int port;

	@BeforeAll
	static void startUpstream() throws IOException {
		upstream = new MockWebServer();
		upstream.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				return new MockResponse().setResponseCode(200);
			}
		});
		upstream.start();
	}

	@AfterAll
	static void stopUpstream() throws IOException {
		upstream.shutdown();
	}

	@DynamicPropertySource
	static void upstreamProperties(DynamicPropertyRegistry registry) {
		registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:" + upstream.getPort());
		registry.add("RATE_LIMIT_REPLENISH_RATE", () -> 1);
		registry.add("RATE_LIMIT_BURST_CAPACITY", () -> BURST);
	}

	static void redisAt(DynamicPropertyRegistry registry, GenericContainer<?> redis) {
		redis.start();
		registry.add("REDIS_HOST", redis::getHost);
		registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
	}

	List<EntityExchangeResult<Void>> send(String apiKey, int count) {
		List<EntityExchangeResult<Void>> results = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			results.add(WebTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.responseTimeout(Duration.ofSeconds(10))
				.build()
				.get()
				.uri("/api/v1/payments")
				.header("X-API-Key", apiKey)
				.exchange()
				.expectBody(Void.class)
				.returnResult());
		}
		return results;
	}

	static List<Integer> statuses(List<EntityExchangeResult<Void>> results) {
		return results.stream().map((result) -> result.getStatus().value()).toList();
	}

}
