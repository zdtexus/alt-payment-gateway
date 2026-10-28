package com.example.api_gateway;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import com.example.api_gateway.ratelimit.RateLimitProperties;

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
	void rateLimitersUseConfiguredDefaults() {
		assertThat(context.getBeansOfType(RedisRateLimiter.class)).containsOnlyKeys("apiKeyRateLimiter",
				"clientIpRateLimiter", "webhookRateLimiter");
		RateLimitProperties properties = context.getBean(RateLimitProperties.class);
		assertThat(properties.apiKey()).isEqualTo(new RateLimitProperties.Limit(10, 20));
		assertThat(properties.clientIp()).isEqualTo(new RateLimitProperties.Limit(20, 40));
		assertThat(properties.webhook()).isEqualTo(new RateLimitProperties.Limit(50, 100));
		assertThat(context.getBean("apiKeyRateLimiter", RedisRateLimiter.class).isIncludeHeaders()).isTrue();
		assertThat(context.getBean("clientIpRateLimiter", RedisRateLimiter.class).isIncludeHeaders()).isFalse();
		assertThat(context.getBean("webhookRateLimiter", RedisRateLimiter.class).isIncludeHeaders()).isTrue();
	}

}
