package com.example.api_gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rate-limit")
public record RateLimitProperties(Limit apiKey, Limit clientIp, Limit webhook) {

	public RateLimitProperties {
		if (apiKey == null || clientIp == null || webhook == null) {
			throw new IllegalArgumentException(
					"rate-limit.api-key, rate-limit.client-ip and rate-limit.webhook must be set");
		}
	}

	public record Limit(int replenishRate, int burstCapacity) {

		public Limit {
			if (replenishRate < 1) {
				throw new IllegalArgumentException("replenish-rate must be at least 1, was " + replenishRate);
			}
			if (burstCapacity < replenishRate) {
				throw new IllegalArgumentException("burst-capacity must be at least replenish-rate (" + replenishRate
						+ "), was " + burstCapacity);
			}
		}

	}

}
