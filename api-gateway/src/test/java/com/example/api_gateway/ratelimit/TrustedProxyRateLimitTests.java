package com.example.api_gateway.ratelimit;

import java.io.IOException;
import java.time.Duration;
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
class TrustedProxyRateLimitTests {

	private static final int BURST = 3;

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
		registry.add("TRUSTED_PROXIES", () -> "127.0.0.1, ::1");
		registry.add("RATE_LIMIT_REPLENISH_RATE", () -> 1);
		registry.add("RATE_LIMIT_BURST_CAPACITY", () -> BURST);
	}

	@BeforeEach
	void clearRedis() {
		redis.execute((connection) -> connection.serverCommands().flushAll()).blockLast(Duration.ofSeconds(5));
	}

	@Test
	void clientsBehindTrustedProxyGetSeparateBuckets() {
		int allowed = 0;
		while (allowed <= BURST + 5 && status(PAYMENTS, "198.51.100.1") == 200) {
			allowed++;
		}

		assertThat(allowed).isBetween(BURST, BURST + 1);
		assertThat(status(PAYMENTS, "198.51.100.2")).isEqualTo(200);
		assertThat(status(PAYMENTS, "198.51.100.1")).isEqualTo(429);
	}

	@Test
	void bucketsAreKeyedByTheForwardedClient() {
		status(PAYMENTS, "198.51.100.1");
		status(WEBHOOK, "198.51.100.3");

		List<String> keys = redis.keys("*").collectList().block(Duration.ofSeconds(5));
		assertThat(keys).anyMatch((key) -> key.contains(".ip:198.51.100.1}"))
			.anyMatch((key) -> key.contains(".client-ip:198.51.100.1}"))
			.anyMatch((key) -> key.contains(".webhook-ip:198.51.100.3}"))
			.noneMatch((key) -> key.contains("127.0.0.1") || key.contains("0:0:0:0:0:0:0:1"));
	}

	@Test
	void forgedLeftMostEntryDoesNotChooseTheBucket() {
		int allowed = 0;
		while (allowed <= BURST + 5 && status(PAYMENTS, "192.0.2." + allowed + ", 198.51.100.1") == 200) {
			allowed++;
		}

		assertThat(allowed).isBetween(BURST, BURST + 1);
	}

	private int status(String path, String forwardedFor) {
		return WebTestClient.bindToServer()
			.baseUrl("http://localhost:" + port)
			.build()
			.get()
			.uri(path)
			.header("X-Forwarded-For", forwardedFor)
			.exchange()
			.returnResult(Void.class)
			.getStatus()
			.value();
	}

}
