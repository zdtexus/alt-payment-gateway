package com.example.api_gateway.redis;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RedisAuthenticationTests extends GatewayAgainstRedisTestSupport {

	@Autowired
	private ReactiveStringRedisTemplate redis;

	@Autowired
	private ApplicationContext context;

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		redisAt(registry, AUTH_REDIS);
		registry.add("REDIS_USERNAME", () -> USERNAME);
		registry.add("REDIS_PASSWORD", () -> PASSWORD);
	}

	@Test
	void correctUsernameAndPasswordConnect() {
		assertThat(redis.opsForValue().set("auth-test", "ok", Duration.ofSeconds(10)).block(Duration.ofSeconds(5)))
			.isTrue();
		assertThat(redis.opsForValue().get("auth-test").block(Duration.ofSeconds(5))).isEqualTo("ok");
	}

	@Test
	void rateLimitIsEnforcedThroughTheAuthenticatedRedis() {
		assertThat(statuses(send("auth-key", BURST + 3))).contains(429);
		assertThat(redis.keys("*").collectList().block(Duration.ofSeconds(5)))
			.anyMatch((key) -> key.matches("request_rate_limiter\\.\\{payment-service\\.key:[0-9a-f]{64}}\\.tokens"))
			.noneMatch((key) -> key.contains("auth-key"));
	}

	@Test
	void passwordIsNeverLoggedOrPrinted(CapturedOutput output) {
		send("auth-key-log", 2);

		assertThat(output).contains("Rate limits stored in Redis at " + AUTH_REDIS.getHost() + ":"
				+ AUTH_REDIS.getMappedPort(6379) + " (TLS off)")
			.doesNotContain(PASSWORD);
		assertThat(context.getBean(DataRedisProperties.class).toString()).doesNotContain(PASSWORD);
		assertThat(context.getBean(DataRedisConnectionDetails.class).toString()).doesNotContain(PASSWORD);
		assertThat(context.getBean(ReactiveRedisConnectionFactory.class).toString()).doesNotContain(PASSWORD);
	}

}
