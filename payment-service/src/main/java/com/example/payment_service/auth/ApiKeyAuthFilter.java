package com.example.payment_service.auth;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.error.InvalidApiKeyException;
import com.example.payment_service.error.MerchantSuspendedException;
import com.example.payment_service.repository.MerchantRepository;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

	public static final String API_KEY_HEADER = "X-API-Key";

	static final String MERCHANT_ATTRIBUTE = ApiKeyAuthFilter.class.getName() + ".merchant";

	private static final List<PathPattern> SKIPPED_PATHS = Stream
		.of("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/admin/**",
			"/api/v1/providers/*/webhook")
		.map(PathPatternParser.defaultInstance::parse)
		.toList();

	private final MerchantRepository merchantRepository;

	private final HandlerExceptionResolver exceptionResolver;

	public ApiKeyAuthFilter(MerchantRepository merchantRepository,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
		this.merchantRepository = merchantRepository;
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		PathContainer path = PathContainer.parsePath(request.getRequestURI());
		return SKIPPED_PATHS.stream().anyMatch((pattern) -> pattern.matches(path));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Merchant merchant;
		try {
			merchant = authenticate(request);
		}
		catch (InvalidApiKeyException | MerchantSuspendedException ex) {
			exceptionResolver.resolveException(request, response, null, ex);
			return;
		}
		request.setAttribute(MERCHANT_ATTRIBUTE, merchant);
		chain.doFilter(request, response);
	}

	private Merchant authenticate(HttpServletRequest request) {
		String apiKey = request.getHeader(API_KEY_HEADER);
		if (!StringUtils.hasText(apiKey)) {
			throw new InvalidApiKeyException();
		}
		Merchant merchant = merchantRepository.findByApiKeyHash(ApiKeyHasher.hash(apiKey))
			.orElseThrow(InvalidApiKeyException::new);
		if (merchant.getStatus() == MerchantStatus.SUSPENDED) {
			throw new MerchantSuspendedException();
		}
		return merchant;
	}

}
