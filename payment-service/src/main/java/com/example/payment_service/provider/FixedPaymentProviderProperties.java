package com.example.payment_service.provider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.provider.fixed")
public record FixedPaymentProviderProperties(@DefaultValue("approve") @NotNull Outcome outcome,
		@DefaultValue("Declined by fixed provider") @NotBlank @Size(max = 255) String declineReason) {

	public enum Outcome {

		APPROVE, DECLINE

	}

}
