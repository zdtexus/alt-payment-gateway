package com.example.api_gateway.redis;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RedisWrongPasswordTests extends GatewayAgainstRedisTestSupport {

	private static final String WRONG_PASSWORD = "wrong-redis-secret-3Kp";

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		redisAt(registry, AUTH_REDIS);
		registry.add("REDIS_USERNAME", () -> USERNAME);
		registry.add("REDIS_PASSWORD", () -> WRONG_PASSWORD);
	}

	@Test
	void wrongPasswordFailsOpenQuickly(CapturedOutput output) {
		long start = System.nanoTime();
		List<EntityExchangeResult<Void>> results = send("wrong-pass-key", BURST + 3);

		assertThat(statuses(results)).containsOnly(200);
		assertThat(results).allSatisfy((result) -> assertThat(
				result.getResponseHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("-1"));
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
		assertThat(output).doesNotContain(WRONG_PASSWORD).doesNotContain(PASSWORD);
	}

}
