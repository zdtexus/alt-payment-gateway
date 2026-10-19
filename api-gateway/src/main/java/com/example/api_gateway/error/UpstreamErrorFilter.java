package com.example.api_gateway.error;

import java.net.ConnectException;
import java.net.UnknownHostException;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@Component
public class UpstreamErrorFilter implements GlobalFilter {

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		return chain.filter(exchange).onErrorMap(UpstreamErrorFilter::isUpstreamFailure, UpstreamErrorFilter::toStatus);
	}

	private static boolean isUpstreamFailure(Throwable ex) {
		return statusFor(ex) != null;
	}

	private static ResponseStatusException toStatus(Throwable ex) {
		HttpStatus status = statusFor(ex);
		String reason = (status == HttpStatus.SERVICE_UNAVAILABLE) ? "Payment service is unavailable"
				: "Payment service returned an invalid response";
		return new ResponseStatusException(status, reason, ex);
	}

	private static HttpStatus statusFor(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
				return HttpStatus.SERVICE_UNAVAILABLE;
			}
			if (cause instanceof PrematureCloseException) {
				return HttpStatus.BAD_GATEWAY;
			}
		}
		return null;
	}

}
