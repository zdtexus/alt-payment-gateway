package com.example.api_gateway.ratelimit;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Node;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class RateLimitBackendLoggerTests {

	private static final String PASSWORD = "logger-test-secret-4Hn";

	@Test
	void describesStandaloneRedis() {
		assertThat(RateLimitBackendLogger.describe(standalone("redis", 6379, null), false))
			.isEqualTo("Rate limits stored in Redis at redis:6379 (TLS off)");
	}

	@Test
	void showsTlsFromTheSettingOrAnSslBundle() {
		assertThat(RateLimitBackendLogger.describe(standalone("redis.internal", 6380, null), true))
			.isEqualTo("Rate limits stored in Redis at redis.internal:6380 (TLS on)");
		assertThat(RateLimitBackendLogger.describe(standalone("redis.internal", 6380, mock(SslBundle.class)), false))
			.isEqualTo("Rate limits stored in Redis at redis.internal:6380 (TLS on)");
	}

	@Test
	void bracketsIpv6Hosts() {
		assertThat(RateLimitBackendLogger.describe(standalone("::1", 6379, null), false))
			.isEqualTo("Rate limits stored in Redis at [::1]:6379 (TLS off)");
	}

	@Test
	void describesClusterSentinelAndMasterReplica() {
		List<Node> nodes = List.of(new Node("redis-1", 7000), new Node("redis-2", 7001));

		assertThat(RateLimitBackendLogger.describe(new WithDefaultStandalone() {
			@Override
			public Cluster getCluster() {
				return () -> nodes;
			}
		}, false)).isEqualTo("Rate limits stored in Redis Cluster at redis-1:7000, redis-2:7001 (TLS off)");

		assertThat(RateLimitBackendLogger.describe(new WithDefaultStandalone() {
			@Override
			public Sentinel getSentinel() {
				return new Sentinel() {
					@Override
					public int getDatabase() {
						return 0;
					}

					@Override
					public String getMaster() {
						return "payments";
					}

					@Override
					public List<Node> getNodes() {
						return nodes;
					}

					@Override
					public String getUsername() {
						return "sentinel-user";
					}

					@Override
					public String getPassword() {
						return PASSWORD;
					}
				};
			}
		}, true)).isEqualTo("Rate limits stored in Redis Sentinel master payments via redis-1:7000, redis-2:7001 (TLS on)");

		assertThat(RateLimitBackendLogger.describe(new WithDefaultStandalone() {
			@Override
			public MasterReplica getMasterReplica() {
				return () -> nodes;
			}
		}, false)).isEqualTo("Rate limits stored in Redis master/replica at redis-1:7000, redis-2:7001 (TLS off)");
	}

	@Test
	void logsTheConfiguredEndpointOnceWithoutCredentials(CapturedOutput output) {
		new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
			.withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
			.withUserConfiguration(RateLimitBackendLogger.class)
			.withPropertyValues("REDIS_HOST=redis.example.internal", "REDIS_PORT=6390", "REDIS_SSL=true",
					"REDIS_USERNAME=gateway", "REDIS_PASSWORD=" + PASSWORD)
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output).containsOnlyOnce("Rate limits stored in Redis at redis.example.internal:6390 (TLS on)")
			.doesNotContain(PASSWORD)
			.doesNotContain("gateway@");
	}

	private abstract static class WithDefaultStandalone implements DataRedisConnectionDetails {

		@Override
		public Standalone getStandalone() {
			return Standalone.of("localhost", 6379);
		}

	}

	private static DataRedisConnectionDetails standalone(String host, int port, SslBundle bundle) {
		return new DataRedisConnectionDetails() {
			@Override
			public Standalone getStandalone() {
				return Standalone.of(host, port);
			}

			@Override
			public String getPassword() {
				return PASSWORD;
			}

			@Override
			public SslBundle getSslBundle() {
				return bundle;
			}
		};
	}

}
