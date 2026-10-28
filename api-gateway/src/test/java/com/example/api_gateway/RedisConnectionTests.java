package com.example.api_gateway;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(RedisTestcontainersConfiguration.class)
class RedisConnectionTests {

	@Autowired
	private ReactiveStringRedisTemplate redisTemplate;

	@Autowired
	private ApplicationContext context;

	@Test
	void gatewayReadsAndWritesRedis() {
		Boolean written = redisTemplate.opsForValue()
			.set("redis-connection-test", "ok", Duration.ofSeconds(10))
			.block(Duration.ofSeconds(5));

		assertThat(written).isTrue();
		assertThat(redisTemplate.opsForValue().get("redis-connection-test").block(Duration.ofSeconds(5)))
			.isEqualTo("ok");
	}

	@Test
	void redisRateLimiterIsAvailable() {
		assertThat(context.getBeansOfType(RedisRateLimiter.class)).hasSize(1);
	}

}
