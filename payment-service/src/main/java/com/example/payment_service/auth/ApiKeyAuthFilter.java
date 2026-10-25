package com.example.payment_service.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.error.InvalidApiKeyException;
import com.example.payment_service.error.MerchantSuspendedException;
import com.example.payment_service.repository.MerchantRepository;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

	static final String API_KEY_HEADER = "X-API-Key";

	private final MerchantRepository merchantRepository;

	public ApiKeyAuthFilter(MerchantRepository merchantRepository) {
		this.merchantRepository = merchantRepository;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		authenticate(request);
		chain.doFilter(request, response);
	}

	private Merchant authenticate(HttpServletRequest request) {
		String apiKey = request.getHeader(API_KEY_HEADER);
		if (!StringUtils.hasText(apiKey)) {
			throw new InvalidApiKeyException();
		}
		Merchant merchant = merchantRepository.findByApiKey(apiKey).orElseThrow(InvalidApiKeyException::new);
		if (merchant.getStatus() == MerchantStatus.SUSPENDED) {
			throw new MerchantSuspendedException();
		}
		return merchant;
	}

}
