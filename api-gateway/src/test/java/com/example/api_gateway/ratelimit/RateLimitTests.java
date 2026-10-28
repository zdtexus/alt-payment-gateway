package com.example.api_gateway.ratelimit;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.example.api_gateway.RedisTestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RedisTestcontainersConfiguration.class)
class RateLimitTests {

	private static final int BURST = 3;

	private static MockWebServer upstream;

	@LocalServerPort
	private int port;

	@Autowired
	private ReactiveStringRedisTemplate redis;

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

	@BeforeEach
	void clearRedis() {
		redis.execute((connection) -> connection.serverCommands().flushAll()).blockLast(Duration.ofSeconds(5));
	}

	@Test
	void requestsBeyondBurstGet429WhileAnotherKeyIsUnaffected() {
		List<Integer> statuses = send("/api/v1/payments", "key-one", BURST + 3);

		assertWithinBurstThenLimited(statuses);
		assertThat(statusOf("/api/v1/payments", "key-two")).isEqualTo(200);
		assertThat(statusOf("/api/v1/payments", "key-one")).isEqualTo(429);
	}

	@Test
	void requestsWithoutKeyAreLimitedByClientIpSeparatelyFromKeys() {
		send("/api/v1/payments", "key-one", BURST + 3);

		assertWithinBurstThenLimited(send("/api/v1/payments", null, BURST + 3));
		assertThat(statusOf("/api/v1/payments", "key-two")).isEqualTo(200);
	}

	@Test
	void limitedResponseCarriesRateLimitHeaders() {
		send("/api/v1/payments", "key-one", BURST + 3);

		client().get()
			.uri("/api/v1/payments")
			.header("X-API-Key", "key-one")
			.exchange()
			.expectStatus()
			.isEqualTo(429)
			.expectHeader()
			.valueEquals("X-RateLimit-Remaining", "0")
			.expectHeader()
			.valueEquals("X-RateLimit-Burst-Capacity", String.valueOf(BURST));
	}

	@Test
	void redisStoresOnlyTheKeyHash() {
		send("/api/v1/payments", "secret-merchant-key", 1);

		List<String> keys = redis.keys("*").collectList().block(Duration.ofSeconds(5));
		assertThat(keys).isNotEmpty().noneMatch((key) -> key.contains("secret-merchant-key"));
		assertThat(keys).anyMatch((key) -> key.contains("key:") && key.matches(".*key:[0-9a-f]{64}.*"));
	}

	@Test
	void adminAndDocsPathsAreNotLimited() {
		assertThat(send("/admin/v1/merchants", "key-one", BURST + 3)).containsOnly(200);
		assertThat(send("/v3/api-docs", "key-one", BURST + 3)).containsOnly(200);
		assertThat(redis.keys("*").collectList().block(Duration.ofSeconds(5))).isEmpty();
	}

	private static void assertWithinBurstThenLimited(List<Integer> statuses) {
		assertThat(statuses.subList(0, BURST)).containsOnly(200);
		assertThat(statuses.getLast()).isEqualTo(429);
		assertThat(statuses.stream().filter((status) -> status == 200).count()).isBetween((long) BURST,
				(long) BURST + 1);
	}

	private List<Integer> send(String path, String apiKey, int count) {
		List<Integer> statuses = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			statuses.add(statusOf(path, apiKey));
		}
		return statuses;
	}

	private int statusOf(String path, String apiKey) {
		WebTestClient.RequestHeadersSpec<?> request = client().get().uri(path);
		if (apiKey != null) {
			request.header("X-API-Key", apiKey);
		}
		return request.exchange().returnResult(Void.class).getStatus().value();
	}

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

}
