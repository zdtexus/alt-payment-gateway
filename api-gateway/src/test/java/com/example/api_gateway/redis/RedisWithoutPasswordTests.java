package com.example.api_gateway.redis;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisWithoutPasswordTests extends GatewayAgainstRedisTestSupport {

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		redisAt(registry, PLAIN_REDIS);
	}

	@Test
	void emptyDefaultsWorkWithARedisThatHasNoPassword() {
		assertThat(statuses(send("plain-key", BURST + 3))).contains(429);
	}

}
