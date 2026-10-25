package com.example.payment_service.provider;

import java.time.Duration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.provider")
public record PaymentProviderProperties(
		@DefaultValue("0.9") @DecimalMin("0.0") @DecimalMax("1.0") double successRate,
		@DefaultValue("300ms") @NotNull Duration latency) {
}
