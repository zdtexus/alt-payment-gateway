package com.example.payment_service.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("admin")
public record AdminProperties(
		@NotBlank(message = "admin.api-key (ADMIN_API_KEY) is required") @Size(min = 16,
				message = "admin.api-key (ADMIN_API_KEY) must be at least 16 characters") String apiKey) {

	@Override
	public String toString() {
		return "AdminProperties[apiKey=***]";
	}

}
