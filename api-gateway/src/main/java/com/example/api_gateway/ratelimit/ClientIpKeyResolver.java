package com.example.api_gateway.ratelimit;

import java.net.InetSocketAddress;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component("clientIpKeyResolver")
public class ClientIpKeyResolver implements KeyResolver {

	static final String CHECKED_ATTRIBUTE = ClientIpKeyResolver.class.getName() + ".checked";

	@Override
	public Mono<String> resolve(ServerWebExchange exchange) {
		exchange.getAttributes().put(CHECKED_ATTRIBUTE, Boolean.TRUE);
		return Mono.just("client-ip:" + clientIp(exchange));
	}

	static boolean wasChecked(ServerWebExchange exchange) {
		return Boolean.TRUE.equals(exchange.getAttribute(CHECKED_ATTRIBUTE));
	}

	static String clientIp(ServerWebExchange exchange) {
		InetSocketAddress address = exchange.getRequest().getRemoteAddress();
		if (address == null || address.getAddress() == null) {
			return "unknown";
		}
		return address.getAddress().getHostAddress();
	}

}
