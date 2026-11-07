package com.example.api_gateway.ratelimit;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ClientIpKeyResolverTests {

	private static final String PROXY = "10.0.0.1";

	private final ClientIpKeyResolver resolver = new ClientIpKeyResolver(
			TrustedProxies.valueOf("10.0.0.0/8, ::1"));

	@Test
	void untrustedPeerWithForgedHeaderKeepsThePeer() {
		assertThat(clientIp("203.0.113.7", "198.51.100.1")).isEqualTo("203.0.113.7");
	}

	@Test
	void withoutTrustedProxiesTheHeaderIsAlwaysIgnored() {
		ClientIpKeyResolver noProxies = new ClientIpKeyResolver(TrustedProxies.none());

		assertThat(noProxies.clientIp(exchange(PROXY, "198.51.100.1"))).isEqualTo(PROXY);
	}

	@Test
	void trustedPeerGivesTheForwardedClient() {
		assertThat(clientIp(PROXY, "203.0.113.7")).isEqualTo("203.0.113.7");
	}

	@Test
	void trustedPeerGivesTheRightMostUntrustedAddressNotAForgedOneOnTheLeft() {
		assertThat(clientIp(PROXY, "198.51.100.1, 203.0.113.7")).isEqualTo("203.0.113.7");
	}

	@Test
	void chainOfTrustedProxiesIsSkipped() {
		assertThat(clientIp(PROXY, "198.51.100.1, 203.0.113.7, 10.0.0.2, 10.20.0.3")).isEqualTo("203.0.113.7");
	}

	@Test
	void onlyTrustedHopsGiveTheLeftMostOne() {
		assertThat(clientIp(PROXY, "10.0.0.5, 10.0.0.2")).isEqualTo("10.0.0.5");
	}

	@Test
	void trustedPeerWithoutHeaderKeepsThePeer() {
		assertThat(clientIp(PROXY, null)).isEqualTo(PROXY);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "unknown", "203.0.113.7, unknown", "203.0.113.7, garbage, 10.0.0.2",
			"10.1", "203.0.113.256", "_hidden", "[2001:db8::7", "[2001:db8::7]x", "203.0.113.7:99999",
			"2001:db8::7:443:x" })
	void malformedEntryKeepsThePeer(String header) {
		assertThat(clientIp(PROXY, header)).isEqualTo(PROXY);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "203.0.113.7:8080 | 203.0.113.7", "[2001:db8::7]:443 | 2001:db8:0:0:0:0:0:7",
			"[2001:db8::7] | 2001:db8:0:0:0:0:0:7", "2001:db8::7 | 2001:db8:0:0:0:0:0:7",
			"' 203.0.113.7 ' | 203.0.113.7" })
	void acceptsPortsBracketsAndWhitespace(String header, String expected) {
		assertThat(clientIp(PROXY, header)).isEqualTo(expected);
	}

	@Test
	void ipv6TrustedPeerGivesTheForwardedClient() {
		assertThat(clientIp("::1", "2001:db8::7, ::1")).isEqualTo("2001:db8:0:0:0:0:0:7");
	}

	@Test
	void severalHeaderLinesAreReadAsOneListInOrder() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")
			.header("X-Forwarded-For", "198.51.100.1")
			.header("X-Forwarded-For", "203.0.113.7")
			.remoteAddress(address(PROXY)));

		assertThat(resolver.clientIp(exchange)).isEqualTo("203.0.113.7");
	}

	@Test
	void unknownPeerStaysUnknown() {
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/").header("X-Forwarded-For", "203.0.113.7"));

		assertThat(resolver.clientIp(exchange)).isEqualTo("unknown");
	}

	@Test
	void allThreeBucketsUseTheForwardedClient() {
		assertThat(resolver.resolve(exchange(PROXY, "203.0.113.7")).block()).isEqualTo("client-ip:203.0.113.7");
		assertThat(new ApiKeyRateLimitKeyResolver(resolver).resolve(exchange(PROXY, "203.0.113.7")).block())
			.isEqualTo("ip:203.0.113.7");
		assertThat(new RateLimitConfig().webhookIpKeyResolver(resolver).resolve(exchange(PROXY, "203.0.113.7")).block())
			.isEqualTo("webhook-ip:203.0.113.7");
	}

	@Test
	void logsWhetherForwardedHeadersAreUsed(CapturedOutput output) {
		new ClientIpKeyResolver(TrustedProxies.none());
		new ClientIpKeyResolver(TrustedProxies.valueOf("10.0.0.0/8"));

		assertThat(output).contains("No trusted proxies: the client IP is the TCP peer and X-Forwarded-For is ignored")
			.contains("Trusted proxies [10.0.0.0/8]: X-Forwarded-For is read only when the TCP peer is one of them");
	}

	private String clientIp(String peer, String forwardedFor) {
		return resolver.clientIp(exchange(peer, forwardedFor));
	}

	private static MockServerWebExchange exchange(String peer, String forwardedFor) {
		MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/").remoteAddress(address(peer));
		if (forwardedFor != null) {
			request.header("X-Forwarded-For", forwardedFor);
		}
		return MockServerWebExchange.from(request);
	}

	private static InetSocketAddress address(String ip) {
		return new InetSocketAddress(InetAddress.ofLiteral(ip), 54321);
	}

}
