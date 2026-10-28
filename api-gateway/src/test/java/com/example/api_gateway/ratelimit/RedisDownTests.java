package com.example.api_gateway.ratelimit;

import java.io.IOException;
import java.time.Duration;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisDownTests {

	private static final int BURST = 3;

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
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:" + upstream.getPort());
		registry.add("RATE_LIMIT_REPLENISH_RATE", () -> 1);
		registry.add("RATE_LIMIT_BURST_CAPACITY", () -> BURST);
	}

	@Test
	void requestsAreLetThroughQuicklyWhenRedisIsUnreachable() {
		int upstreamBefore = upstream.getRequestCount();

		for (int i = 0; i < BURST + 3; i++) {
			long start = System.nanoTime();
			WebTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.responseTimeout(Duration.ofSeconds(10))
				.build()
				.get()
				.uri("/api/v1/payments")
				.header("X-API-Key", "key-one")
				.exchange()
				.expectStatus()
				.isOk()
				.expectHeader()
				.valueEquals("X-RateLimit-Remaining", "-1")
				.expectHeader()
				.doesNotExist("Retry-After");
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
		}
		assertThat(upstream.getRequestCount() - upstreamBefore).isEqualTo(BURST + 3);
	}

}
