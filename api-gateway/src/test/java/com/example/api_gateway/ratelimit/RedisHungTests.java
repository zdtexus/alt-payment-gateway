package com.example.api_gateway.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.github.dockerjava.api.DockerClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.example.api_gateway.RedisTestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RedisTestcontainersConfiguration.class)
class RedisHungTests {

	private static final int BURST = 3;

	private static MockWebServer upstream;

	@LocalServerPort
	private int port;

	@Autowired
	private GenericContainer<?> redisContainer;

	private boolean paused;

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

	@AfterEach
	void unpauseRedis() {
		if (paused) {
			setPaused(false);
		}
	}

	@Test
	void hungRedisLetsRequestsThroughWithinTimeoutAndLimitingResumesAfterwards() {
		assertThat(statusesFor("key-one", BURST + 3)).contains(429);

		setPaused(true);
		for (int i = 0; i < 3; i++) {
			long start = System.nanoTime();
			assertThat(status("key-one")).isEqualTo(200);
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
		}

		setPaused(false);
		assertThat(statusesFor("key-one", BURST + 5)).contains(429);
	}

	private List<Integer> statusesFor(String apiKey, int count) {
		List<Integer> statuses = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			statuses.add(status(apiKey));
		}
		return statuses;
	}

	private int status(String apiKey) {
		return WebTestClient.bindToServer()
			.baseUrl("http://localhost:" + port)
			.responseTimeout(Duration.ofSeconds(10))
			.build()
			.get()
			.uri("/api/v1/payments")
			.header("X-API-Key", apiKey)
			.exchange()
			.returnResult(Void.class)
			.getStatus()
			.value();
	}

	private void setPaused(boolean pause) {
		DockerClient docker = DockerClientFactory.instance().client();
		if (pause) {
			docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
		}
		else {
			docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
		}
		paused = pause;
	}

}
