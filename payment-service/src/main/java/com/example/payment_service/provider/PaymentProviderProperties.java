package com.example.payment_service.provider;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.example.payment_service.entity.PaymentMethod;

@ConfigurationProperties("payment.provider")
public record PaymentProviderProperties(@DefaultValue(SimulatedPaymentProvider.NAME) String defaultProvider,
		Map<PaymentMethod, String> routes) {

	public PaymentProviderProperties {
		routes = (routes != null) ? Map.copyOf(routes) : Map.of();
	}

}
