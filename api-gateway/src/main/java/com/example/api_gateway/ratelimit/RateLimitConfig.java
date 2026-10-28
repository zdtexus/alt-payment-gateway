package com.example.api_gateway.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

	@Bean
	@Primary
	RedisRateLimiter apiKeyRateLimiter(RateLimitProperties properties) {
		RateLimitProperties.Limit limit = properties.apiKey();
		return new RedisRateLimiter(limit.replenishRate(), limit.burstCapacity(), 1);
	}

	@Bean
	RedisRateLimiter clientIpRateLimiter(RateLimitProperties properties) {
		RateLimitProperties.Limit limit = properties.clientIp();
		RedisRateLimiter limiter = new RedisRateLimiter(limit.replenishRate(), limit.burstCapacity(), 1);
		limiter.setIncludeHeaders(false);
		return limiter;
	}

}
