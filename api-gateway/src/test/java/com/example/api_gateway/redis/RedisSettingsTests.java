package com.example.api_gateway.redis;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RedisSettingsTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(PropertiesConfig.class);

	@Test
	void noCredentialsAndNoTlsByDefault() {
		runner.run((context) -> {
			DataRedisProperties redis = context.getBean(DataRedisProperties.class);
			assertThat(StringUtils.hasText(redis.getUsername())).isFalse();
			assertThat(StringUtils.hasText(redis.getPassword())).isFalse();
			assertThat(redis.getSsl().isEnabled()).isFalse();
		});
	}

	@Test
	void environmentVariablesSetCredentialsAndTls() {
		runner.withPropertyValues("REDIS_USERNAME=gateway", "REDIS_PASSWORD=env-secret", "REDIS_SSL=true")
			.run((context) -> {
				DataRedisProperties redis = context.getBean(DataRedisProperties.class);
				assertThat(redis.getUsername()).isEqualTo("gateway");
				assertThat(redis.getPassword()).isEqualTo("env-secret");
				assertThat(redis.getSsl().isEnabled()).isTrue();
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(DataRedisProperties.class)
	static class PropertiesConfig {

	}

}
