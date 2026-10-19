package com.example.api_gateway.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webflux.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GatewayErrorAttributes extends DefaultErrorAttributes {

	@Override
	public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
		Map<String, Object> defaults = super.getErrorAttributes(request, options);
		HttpStatus status = HttpStatus.valueOf((int) defaults.get("status"));
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("timestamp", defaults.get("timestamp"));
		attributes.put("status", status.value());
		attributes.put("error", status.name());
		attributes.put("message", messageFor(getError(request), status));
		attributes.put("path", defaults.get("path"));
		return attributes;
	}

	private static String messageFor(Throwable error, HttpStatus status) {
		if (error instanceof ResponseStatusException ex && ex.getReason() != null) {
			return ex.getReason();
		}
		return status.getReasonPhrase();
	}

}
