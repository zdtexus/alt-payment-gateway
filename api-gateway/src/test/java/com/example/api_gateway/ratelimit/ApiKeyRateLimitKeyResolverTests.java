package com.example.api_gateway.ratelimit;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyRateLimitKeyResolverTests {

	private static final InetSocketAddress CLIENT = new InetSocketAddress("203.0.113.7", 54321);

	private final ApiKeyRateLimitKeyResolver resolver = new ApiKeyRateLimitKeyResolver();

	@Test
	void apiKeyIsIdentifiedBySha256HashNeverRawKey() {
		String key = resolve(MockServerHttpRequest.get("/api/v1/payments")
			.header("X-API-Key", "demo-key-123")
			.remoteAddress(CLIENT));

		assertThat(key).isEqualTo("key:a52782e3a2d4dd2f95f640b9abfb3b2a6b8e722c65f55be830f6e5f619f7f873")
			.doesNotContain("demo-key-123");
	}

	@Test
	void differentKeysGetDifferentBucketsAndSameKeyTheSameBucket() {
		String first = resolve(MockServerHttpRequest.get("/").header("X-API-Key", "key-one"));
		String second = resolve(MockServerHttpRequest.get("/").header("X-API-Key", "key-two"));

		assertThat(first).isNotEqualTo(second);
		assertThat(resolve(MockServerHttpRequest.get("/").header("X-API-Key", "key-one"))).isEqualTo(first);
	}

	@Test
	void sameKeyFromDifferentIpsSharesOneBucket() {
		String first = resolve(MockServerHttpRequest.get("/").header("X-API-Key", "key-one").remoteAddress(CLIENT));
		String second = resolve(MockServerHttpRequest.get("/")
			.header("X-API-Key", "key-one")
			.remoteAddress(new InetSocketAddress("198.51.100.1", 1234)));

		assertThat(first).isEqualTo(second);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " " })
	void blankKeyFallsBackToClientIp(String apiKey) {
		assertThat(resolve(MockServerHttpRequest.get("/").header("X-API-Key", apiKey).remoteAddress(CLIENT)))
			.isEqualTo("ip:203.0.113.7");
	}

	@Test
	void missingKeyFallsBackToClientIp() {
		assertThat(resolve(MockServerHttpRequest.get("/").remoteAddress(CLIENT))).isEqualTo("ip:203.0.113.7");
	}

	@Test
	void forwardedForHeaderIsIgnored() {
		assertThat(resolve(MockServerHttpRequest.get("/").header("X-Forwarded-For", "192.0.2.99").remoteAddress(CLIENT)))
			.isEqualTo("ip:203.0.113.7");
	}

	@Test
	void unknownClientAddressStillGetsABucket() {
		assertThat(resolve(MockServerHttpRequest.get("/"))).isEqualTo("ip:unknown");
	}

	@Test
	void clientIpResolverUsesOnlyTheRemoteAddressWhateverTheKey() {
		ClientIpKeyResolver clientIpResolver = new ClientIpKeyResolver();

		assertThat(clientIpResolver.resolve(MockServerWebExchange.from(MockServerHttpRequest.get("/")
			.header("X-API-Key", "key-one")
			.header("X-Forwarded-For", "192.0.2.99")
			.remoteAddress(CLIENT))).block()).isEqualTo("client-ip:203.0.113.7");
		assertThat(clientIpResolver.resolve(MockServerWebExchange.from(MockServerHttpRequest.get("/"))).block())
			.isEqualTo("client-ip:unknown");
	}

	@Test
	void clientIpResolverMarksTheExchangeAsCheckedAndApiKeyResolverDoesNot() {
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/").header("X-API-Key", "key-one").remoteAddress(CLIENT));

		resolver.resolve(exchange).block();
		assertThat(ClientIpKeyResolver.wasChecked(exchange)).isFalse();

		new ClientIpKeyResolver().resolve(exchange).block();
		assertThat(ClientIpKeyResolver.wasChecked(exchange)).isTrue();
	}

	private String resolve(MockServerHttpRequest.BaseBuilder<?> request) {
		return resolver.resolve(MockServerWebExchange.from(request)).block();
	}

}
