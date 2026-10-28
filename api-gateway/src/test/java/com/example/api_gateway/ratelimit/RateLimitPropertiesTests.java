package com.example.api_gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfig.class);

	@Test
	void bindsAllLimits() {
		runner
			.withPropertyValues("rate-limit.api-key.replenish-rate=5", "rate-limit.api-key.burst-capacity=8",
					"rate-limit.client-ip.replenish-rate=7", "rate-limit.client-ip.burst-capacity=9",
					"rate-limit.webhook.replenish-rate=30", "rate-limit.webhook.burst-capacity=60")
			.run((context) -> {
				RateLimitProperties properties = context.getBean(RateLimitProperties.class);
				assertThat(properties.apiKey()).isEqualTo(new RateLimitProperties.Limit(5, 8));
				assertThat(properties.clientIp()).isEqualTo(new RateLimitProperties.Limit(7, 9));
				assertThat(properties.webhook()).isEqualTo(new RateLimitProperties.Limit(30, 60));
			});
	}

	@Test
	void failsWhenALimitIsMissing() {
		runner.withPropertyValues("rate-limit.api-key.replenish-rate=5", "rate-limit.api-key.burst-capacity=8")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("rate-limit.api-key, rate-limit.client-ip and rate-limit.webhook must be set"));
	}

	@ParameterizedTest
	@CsvSource({ "0, 5, 'replenish-rate must be at least 1, was 0'",
			"-1, 5, 'replenish-rate must be at least 1, was -1'",
			"10, 5, 'burst-capacity must be at least replenish-rate (10), was 5'" })
	void failsOnInvalidLimit(int replenishRate, int burstCapacity, String message) {
		runner
			.withPropertyValues("rate-limit.api-key.replenish-rate=10", "rate-limit.api-key.burst-capacity=20",
					"rate-limit.client-ip.replenish-rate=" + replenishRate,
					"rate-limit.client-ip.burst-capacity=" + burstCapacity, "rate-limit.webhook.replenish-rate=50",
					"rate-limit.webhook.burst-capacity=100")
			.run((context) -> assertThat(context).hasFailed().getFailure().rootCause().hasMessage(message));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RateLimitProperties.class)
	static class PropertiesConfig {

	}

}
