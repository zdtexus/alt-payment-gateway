package com.example.api_gateway.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

@Component("apiKeyResolver")
@Primary
public class ApiKeyRateLimitKeyResolver implements KeyResolver {

	static final String API_KEY_HEADER = "X-API-Key";

	@Override
	public Mono<String> resolve(ServerWebExchange exchange) {
		String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
		if (StringUtils.hasText(apiKey)) {
			return Mono.just("key:" + sha256(apiKey));
		}
		return Mono.just("ip:" + ClientIpKeyResolver.clientIp(exchange));
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
