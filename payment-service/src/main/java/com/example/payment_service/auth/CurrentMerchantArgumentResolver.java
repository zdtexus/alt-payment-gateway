package com.example.payment_service.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.error.InvalidApiKeyException;

public class CurrentMerchantArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentMerchant.class)
				&& Merchant.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Merchant resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Object merchant = webRequest.getAttribute(ApiKeyAuthFilter.MERCHANT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (merchant == null) {
			throw new InvalidApiKeyException();
		}
		return (Merchant) merchant;
	}

}
