package com.example.api_gateway.ratelimit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

public class ClientIpKeyResolver implements KeyResolver {

	private static final Logger log = LoggerFactory.getLogger(ClientIpKeyResolver.class);

	static final String CHECKED_ATTRIBUTE = ClientIpKeyResolver.class.getName() + ".checked";

	static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

	private static final int MAX_PORT = 65535;

	private final TrustedProxies trustedProxies;

	public ClientIpKeyResolver(TrustedProxies trustedProxies) {
		this.trustedProxies = trustedProxies;
		if (trustedProxies.isEmpty()) {
			log.info("No trusted proxies: the client IP is the TCP peer and X-Forwarded-For is ignored");
		}
		else {
			log.info("Trusted proxies {}: X-Forwarded-For is read only when the TCP peer is one of them",
					trustedProxies);
		}
	}

	@Override
	public Mono<String> resolve(ServerWebExchange exchange) {
		exchange.getAttributes().put(CHECKED_ATTRIBUTE, Boolean.TRUE);
		return Mono.just("client-ip:" + clientIp(exchange));
	}

	static boolean wasChecked(ServerWebExchange exchange) {
		return Boolean.TRUE.equals(exchange.getAttribute(CHECKED_ATTRIBUTE));
	}

	String clientIp(ServerWebExchange exchange) {
		ServerHttpRequest request = exchange.getRequest();
		InetSocketAddress remote = request.getRemoteAddress();
		if (remote == null || remote.getAddress() == null) {
			return "unknown";
		}
		InetAddress peer = remote.getAddress();
		if (!trustedProxies.contains(peer)) {
			return peer.getHostAddress();
		}
		return forwardedClient(request.getHeaders().getValuesAsList(FORWARDED_FOR_HEADER), peer).getHostAddress();
	}

	private InetAddress forwardedClient(List<String> hops, InetAddress peer) {
		InetAddress client = peer;
		for (int i = hops.size() - 1; i >= 0; i--) {
			InetAddress hop = parseHop(hops.get(i));
			if (hop == null) {
				return peer;
			}
			client = hop;
			if (!trustedProxies.contains(hop)) {
				return hop;
			}
		}
		return client;
	}

	private static InetAddress parseHop(String hop) {
		String text = hop.strip();
		if (text.startsWith("[")) {
			int end = text.indexOf(']');
			if (end < 0 || !(end == text.length() - 1 || isPort(text.substring(end + 1)))) {
				return null;
			}
			return TrustedProxies.parseLiteral(text.substring(1, end));
		}
		int colon = text.indexOf(':');
		if (colon >= 0 && colon == text.lastIndexOf(':') && isPort(text.substring(colon))) {
			text = text.substring(0, colon);
		}
		return TrustedProxies.parseLiteral(text);
	}

	private static boolean isPort(String suffix) {
		return suffix.matches(":\\d{1,5}") && Integer.parseInt(suffix.substring(1)) <= MAX_PORT;
	}

}
