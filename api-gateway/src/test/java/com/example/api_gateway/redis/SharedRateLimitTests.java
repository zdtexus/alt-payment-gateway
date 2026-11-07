package com.example.api_gateway.redis;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.example.api_gateway.ApiGatewayApplication;

import static org.assertj.core.api.Assertions.assertThat;

class SharedRateLimitTests {

	private static final int BURST = 5;

	private static final String API_KEY = "shared-key";

	private static final GenericContainer<?> SHARED_REDIS = redis();

	private static final GenericContainer<?> OTHER_REDIS = redis();

	private static MockWebServer upstream;

	private static ConfigurableApplicationContext gatewayA;

	private static ConfigurableApplicationContext gatewayB;

	private static ConfigurableApplicationContext gatewayOnOtherRedis;

	@BeforeAll
	static void startGateways() throws IOException {
		SHARED_REDIS.start();
		OTHER_REDIS.start();
		upstream = new MockWebServer();
		upstream.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				return new MockResponse().setResponseCode(200);
			}
		});
		upstream.start();
		gatewayA = startGateway(SHARED_REDIS);
		gatewayB = startGateway(SHARED_REDIS);
		gatewayOnOtherRedis = startGateway(OTHER_REDIS);
	}

	@AfterAll
	static void stopGateways() throws IOException {
		for (ConfigurableApplicationContext gateway : List.of(gatewayA, gatewayB, gatewayOnOtherRedis)) {
			if (gateway != null) {
				gateway.close();
			}
		}
		upstream.shutdown();
	}

	@BeforeEach
	void clearRedis() throws Exception {
		SHARED_REDIS.execInContainer("redis-cli", "FLUSHALL");
		OTHER_REDIS.execInContainer("redis-cli", "FLUSHALL");
	}

	@Test
	void gatewaysRunAsSeparateInstances() {
		assertThat(List.of(port(gatewayA), port(gatewayB), port(gatewayOnOtherRedis))).doesNotHaveDuplicates();
	}

	@Test
	void twoGatewaysOnOneRedisShareOneLimit() {
		int allowed = 0;
		int sent = 0;
		while (sent < 3 * BURST) {
			ConfigurableApplicationContext gateway = (sent % 2 == 0) ? gatewayA : gatewayB;
			sent++;
			if (status(gateway) == 429) {
				break;
			}
			allowed++;
		}

		assertThat(allowed).isBetween(BURST, BURST + 1);
		assertThat(status(gatewayA)).isEqualTo(429);
		assertThat(status(gatewayB)).isEqualTo(429);
	}

	@Test
	void gatewayOnAnotherRedisHasItsOwnLimit() {
		int allowed = 0;
		for (int i = 0; i < 2 * BURST + 4; i++) {
			ConfigurableApplicationContext gateway = (i % 2 == 0) ? gatewayA : gatewayOnOtherRedis;
			if (status(gateway) == 200) {
				allowed++;
			}
		}

		assertThat(allowed).isGreaterThanOrEqualTo(2 * BURST);
	}

	private static ConfigurableApplicationContext startGateway(GenericContainer<?> redis) {
		return new SpringApplicationBuilder(ApiGatewayApplication.class).run("--server.port=0",
				"--REDIS_HOST=" + redis.getHost(), "--REDIS_PORT=" + redis.getMappedPort(6379),
				"--PAYMENT_SERVICE_URL=http://localhost:" + upstream.getPort(), "--RATE_LIMIT_REPLENISH_RATE=1",
				"--RATE_LIMIT_BURST_CAPACITY=" + BURST);
	}

	private static int port(ConfigurableApplicationContext gateway) {
		return ((WebServerApplicationContext) gateway).getWebServer().getPort();
	}

	private static int status(ConfigurableApplicationContext gateway) {
		return WebTestClient.bindToServer()
			.baseUrl("http://localhost:" + port(gateway))
			.responseTimeout(Duration.ofSeconds(10))
			.build()
			.get()
			.uri("/api/v1/payments")
			.header("X-API-Key", API_KEY)
			.exchange()
			.returnResult(Void.class)
			.getStatus()
			.value();
	}

	private static GenericContainer<?> redis() {
		return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
	}

}
