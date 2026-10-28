package com.example.api_gateway.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.example.api_gateway.RedisTestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RedisTestcontainersConfiguration.class)
class RateLimitTests {

	private static final int BURST = 3;

	private static final int IP_BURST = 10;

	private static final int WEBHOOK_BURST = 6;

	private static final String PAYMENTS = "/api/v1/payments";

	private static final String WEBHOOK = "/api/v1/providers/http/webhook";

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
		registry.add("RATE_LIMIT_IP_REPLENISH_RATE", () -> 1);
		registry.add("RATE_LIMIT_IP_BURST_CAPACITY", () -> IP_BURST);
		registry.add("RATE_LIMIT_WEBHOOK_REPLENISH_RATE", () -> 1);
		registry.add("RATE_LIMIT_WEBHOOK_BURST_CAPACITY", () -> WEBHOOK_BURST);
	}

	@BeforeEach
	void clearRedis() {
		redis.execute((connection) -> connection.serverCommands().flushAll()).blockLast(Duration.ofSeconds(5));
	}

	@Test
	void requestsBeyondBurstGet429WhileAnotherKeyIsUnaffected() {
		Limited limited = sendUntilLimited(() -> "key-one", BURST + 5);

		assertAllowedAboutBurst(limited, BURST);
		assertThat(statusOf(PAYMENTS, "key-two")).isEqualTo(200);
		assertMostlyLimited(send(PAYMENTS, "key-one", 3));
	}

	@Test
	void requestsWithoutKeyAreLimitedByClientIpSeparatelyFromKeys() {
		send(PAYMENTS, "key-one", BURST + 3);

		assertAllowedAboutBurst(sendUntilLimited(() -> null, BURST + 5), BURST);
		assertThat(statusOf(PAYMENTS, "key-two")).isEqualTo(200);
	}

	@Test
	void guessingWithNewKeyPerRequestIsLimitedPerClientIp() {
		Limited limited = sendUntilLimited(() -> "guess-" + UUID.randomUUID(), IP_BURST + 5);

		assertAllowedAboutBurst(limited, IP_BURST);
		HttpHeaders headers = limited.response().getResponseHeaders();
		assertThat(headers.getFirst("Retry-After")).isEqualTo("1");
		assertThat(headers.headerNames()).noneMatch((name) -> name.startsWith("X-RateLimit-"));
		assertThat(JsonPath.<String>read(limited.body(), "$.error")).isEqualTo("TOO_MANY_REQUESTS");
		assertThat(JsonPath.<String>read(limited.body(), "$.message")).isEqualTo("Rate limit exceeded, retry later");
	}

	@Test
	void requestsRefusedPerKeyDoNotUseTheClientIpAllowance() {
		send(PAYMENTS, "key-one", IP_BURST + 5);

		assertThat(send(PAYMENTS, "key-two", BURST)).containsOnly(200);
	}

	@Test
	void limitedResponseUsesCommonErrorFormatWithRetryAfter() {
		int upstreamBefore = upstream.getRequestCount();

		Limited limited = sendUntilLimited(() -> "key-one", BURST + 5);

		HttpHeaders headers = limited.response().getResponseHeaders();
		assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(headers.getFirst("Retry-After")).isEqualTo("1");
		assertThat(headers.get("X-RateLimit-Remaining")).containsExactly("0");
		assertThat(headers.get("X-RateLimit-Burst-Capacity")).containsExactly(String.valueOf(BURST));
		assertThat(JsonPath.<String>read(limited.body(), "$.timestamp")).isNotEmpty();
		assertThat(JsonPath.<Integer>read(limited.body(), "$.status")).isEqualTo(429);
		assertThat(JsonPath.<String>read(limited.body(), "$.error")).isEqualTo("TOO_MANY_REQUESTS");
		assertThat(JsonPath.<String>read(limited.body(), "$.message")).isEqualTo("Rate limit exceeded, retry later");
		assertThat(JsonPath.<String>read(limited.body(), "$.path")).isEqualTo(PAYMENTS);
		assertThat(limited.body()).doesNotContain("requestId");
		assertThat(upstream.getRequestCount() - upstreamBefore).isEqualTo(limited.allowedBefore());
	}

	@Test
	void allowedResponseHasNoRetryAfter() {
		client().get()
			.uri(PAYMENTS)
			.header("X-API-Key", "key-one")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.doesNotExist("Retry-After")
			.expectHeader()
			.valueEquals("X-RateLimit-Remaining", String.valueOf(BURST - 1));
	}

	@Test
	void redisStoresOnlyTheKeyHash() {
		send(PAYMENTS, "secret-merchant-key", 1);

		List<String> keys = redis.keys("*").collectList().block(Duration.ofSeconds(5));
		assertThat(keys).isNotEmpty().noneMatch((key) -> key.contains("secret-merchant-key"));
		assertThat(keys).anyMatch((key) -> key.matches(".*\\.key:[0-9a-f]{64}}.*"));
		assertThat(keys).anyMatch((key) -> key.contains(".client-ip:"));
	}

	@Test
	void webhooksHaveTheirOwnPerIpLimitWithRetryAfter() {
		Limited limited = sendUntilLimited(WEBHOOK, () -> null, WEBHOOK_BURST + 5);

		assertAllowedAboutBurst(limited, WEBHOOK_BURST);
		HttpHeaders headers = limited.response().getResponseHeaders();
		assertThat(headers.getFirst("Retry-After")).isEqualTo("1");
		assertThat(headers.get("X-RateLimit-Burst-Capacity")).containsExactly(String.valueOf(WEBHOOK_BURST));
		assertThat(JsonPath.<String>read(limited.body(), "$.error")).isEqualTo("TOO_MANY_REQUESTS");
		assertThat(JsonPath.<String>read(limited.body(), "$.path")).isEqualTo(WEBHOOK);
	}

	@Test
	void webhooksAndMerchantsDoNotShareLimits() {
		send(WEBHOOK, null, WEBHOOK_BURST + 3);
		assertThat(statusOf(PAYMENTS, null)).isEqualTo(200);
		assertThat(statusOf(PAYMENTS, "key-one")).isEqualTo(200);

		redis.execute((connection) -> connection.serverCommands().flushAll()).blockLast(Duration.ofSeconds(5));
		send(PAYMENTS, null, BURST + 3);
		send(PAYMENTS, "guess-" + UUID.randomUUID(), IP_BURST + 3);
		assertThat(send(WEBHOOK, null, WEBHOOK_BURST - 1)).containsOnly(200);
	}

	@Test
	void webhookLimitIsStoredUnderItsOwnPrefix() {
		send(WEBHOOK, null, 1);

		List<String> keys = redis.keys("*").collectList().block(Duration.ofSeconds(5));
		assertThat(keys).anyMatch((key) -> key.contains(".webhook-ip:"))
			.noneMatch((key) -> key.contains(".client-ip:") || key.contains(".ip:"));
	}

	@Test
	void adminAndDocsPathsAreNotLimited() {
		assertThat(send("/admin/v1/merchants", "key-one", BURST + 3)).containsOnly(200);
		assertThat(send("/v3/api-docs", "key-one", BURST + 3)).containsOnly(200);
		assertThat(redis.keys("*").collectList().block(Duration.ofSeconds(5))).isEmpty();
	}

	private static void assertAllowedAboutBurst(Limited limited, int burst) {
		assertThat(limited.response()).as("no request was limited").isNotNull();
		assertThat(limited.allowedBefore()).isBetween(burst, burst + 1);
	}

	private static void assertMostlyLimited(List<Integer> statuses) {
		assertThat(statuses.stream().filter((status) -> status == 429).count()).isGreaterThanOrEqualTo(statuses.size() - 1);
	}

	private Limited sendUntilLimited(Supplier<String> apiKeys, int maxRequests) {
		return sendUntilLimited(PAYMENTS, apiKeys, maxRequests);
	}

	private Limited sendUntilLimited(String path, Supplier<String> apiKeys, int maxRequests) {
		int allowed = 0;
		for (int i = 0; i < maxRequests; i++) {
			EntityExchangeResult<byte[]> result = request(path, apiKeys.get()).exchange()
				.expectBody()
				.returnResult();
			if (result.getStatus().value() == 429) {
				return new Limited(allowed, result);
			}
			assertThat(result.getStatus().value()).isEqualTo(200);
			allowed++;
		}
		return new Limited(allowed, null);
	}

	private List<Integer> send(String path, String apiKey, int count) {
		List<Integer> statuses = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			statuses.add(statusOf(path, apiKey));
		}
		return statuses;
	}

	private int statusOf(String path, String apiKey) {
		return request(path, apiKey).exchange().returnResult(Void.class).getStatus().value();
	}

	private WebTestClient.RequestHeadersSpec<?> request(String path, String apiKey) {
		WebTestClient.RequestHeadersSpec<?> request = client().get().uri(path);
		if (apiKey != null) {
			request.header("X-API-Key", apiKey);
		}
		return request;
	}

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	private record Limited(int allowedBefore, EntityExchangeResult<byte[]> response) {

		String body() {
			return new String(response.getResponseBodyContent());
		}

	}

}
