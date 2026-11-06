package com.example.payment_service.error;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

@Component
public class ServiceErrorAttributes extends DefaultErrorAttributes {

	@Override
	public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
		Map<String, Object> defaults = super.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());
		HttpStatus status = statusOf(defaults.get("status"));
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("timestamp", Instant.now());
		attributes.put("status", status.value());
		attributes.put("error", status.name());
		attributes.put("message", status.getReasonPhrase());
		attributes.put("path", defaults.get("path"));
		return attributes;
	}

	private static HttpStatus statusOf(Object status) {
		HttpStatus resolved = (status instanceof Integer code) ? HttpStatus.resolve(code) : null;
		return (resolved != null) ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
	}

}
