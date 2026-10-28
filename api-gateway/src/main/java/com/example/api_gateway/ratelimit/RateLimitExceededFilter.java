package com.example.api_gateway.ratelimit;

import java.util.List;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@Component
public class RateLimitExceededFilter implements GlobalFilter, Ordered {

	static final String MESSAGE = "Rate limit exceeded, retry later";

	private static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";

	private static final String REQUESTED_TOKENS_HEADER = "X-RateLimit-Requested-Tokens";

	private static final List<String> RATE_LIMIT_HEADERS = List.of("X-RateLimit-Remaining", REPLENISH_RATE_HEADER,
			REQUESTED_TOKENS_HEADER, "X-RateLimit-Burst-Capacity");

	private final RateLimitProperties properties;

	public RateLimitExceededFilter(RateLimitProperties properties) {
		this.properties = properties;
	}

	@Override
	public int getOrder() {
		return 0;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		return chain.filter(exchange).onErrorMap(RateLimitExceededFilter::isRateLimited, (ex) -> {
			HttpHeaders headers = exchange.getResponse().getHeaders();
			long retryAfter;
			if (ClientIpKeyResolver.wasChecked(exchange)) {
				RATE_LIMIT_HEADERS.forEach(headers::remove);
				retryAfter = secondsToRefill(1, properties.clientIp().replenishRate());
			}
			else {
				retryAfter = retryAfterSeconds(headers);
			}
			headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
			return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, MESSAGE, ex);
		});
	}

	private static boolean isRateLimited(Throwable ex) {
		return ex instanceof HttpClientErrorException.TooManyRequests;
	}

	static long retryAfterSeconds(HttpHeaders headers) {
		return secondsToRefill(parse(headers.getFirst(REQUESTED_TOKENS_HEADER), 1),
				parse(headers.getFirst(REPLENISH_RATE_HEADER), 0));
	}

	private static long secondsToRefill(long requestedTokens, long replenishRate) {
		if (replenishRate <= 0) {
			return 1;
		}
		return Math.max(1, (requestedTokens + replenishRate - 1) / replenishRate);
	}

	private static long parse(String value, long fallback) {
		try {
			return (value != null) ? Long.parseLong(value) : fallback;
		}
		catch (NumberFormatException ex) {
			return fallback;
		}
	}

}
