package com.example.payment_service.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;

public record CreatedMerchantResponse(@Schema(example = "3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f") UUID id,
		@Schema(example = "Acme Store") String name,
		@Schema(example = "billing@acme.example", nullable = true) String email,
		@Schema(example = "+380501234567", nullable = true) String phone,
		@Schema(example = "ACTIVE") MerchantStatus status,
		@Schema(description = "The merchant's API key. It is shown only once and cannot be retrieved later",
				example = "q3Zb8kV1xYwP0nR7sT2uL5mH9cJ4dF6gA1eB3iK8oQ0") String apiKey) {

	public static CreatedMerchantResponse from(Merchant merchant, String apiKey) {
		return new CreatedMerchantResponse(merchant.getId(), merchant.getName(), merchant.getEmail(),
				merchant.getPhone(), merchant.getStatus(), apiKey);
	}

	@Override
	public String toString() {
		return "CreatedMerchantResponse[id=" + id + ", name=" + name + ", status=" + status + ", apiKey=***]";
	}

}
