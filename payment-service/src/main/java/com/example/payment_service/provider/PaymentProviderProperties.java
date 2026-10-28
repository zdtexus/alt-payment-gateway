package com.example.payment_service.provider;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.provider")
public record PaymentProviderProperties(@DefaultValue(SimulatedPaymentProvider.NAME) @NotBlank String defaultProvider) {
}
