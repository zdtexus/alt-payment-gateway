package com.example.payment_service.provider;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("payment.provider.fixed")
public record FixedPaymentProviderProperties(@DefaultValue("approve") @NotNull Outcome outcome,
		@DefaultValue("Declined by fixed provider") @NotBlank @Size(max = 255) String declineReason,
		@DefaultValue("7d") @NotNull Duration authorizationValidity) {

	@AssertTrue(message = "payment.provider.fixed.authorization-validity must be positive")
	public boolean isAuthorizationValidityPositive() {
		return authorizationValidity != null && authorizationValidity.isPositive();
	}

	public enum Outcome {

		APPROVE, DECLINE

	}

}
