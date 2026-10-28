package com.example.payment_service.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.example.payment_service.error.InvalidAdminKeyException;

@Component
@EnableConfigurationProperties(AdminProperties.class)
public class AdminKeyAuthFilter extends OncePerRequestFilter {

	public static final String ADMIN_KEY_HEADER = "X-Admin-Key";

	private static final PathPattern ADMIN_PATHS = PathPatternParser.defaultInstance.parse("/admin/**");

	private final byte[] expectedKeyHash;

	private final HandlerExceptionResolver exceptionResolver;

	public AdminKeyAuthFilter(AdminProperties properties,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
		this.expectedKeyHash = hash(properties.apiKey());
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !ADMIN_PATHS.matches(PathContainer.parsePath(request.getRequestURI()));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String adminKey = request.getHeader(ADMIN_KEY_HEADER);
		if (adminKey == null || !MessageDigest.isEqual(hash(adminKey), expectedKeyHash)) {
			exceptionResolver.resolveException(request, response, null, new InvalidAdminKeyException());
			return;
		}
		chain.doFilter(request, response);
	}

	private static byte[] hash(String key) {
		return ApiKeyHasher.hash(key).getBytes(StandardCharsets.US_ASCII);
	}

}
