package com.example.payment_service.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;

public record MerchantResponse(@Schema(example = "3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f") UUID id,
		@Schema(example = "Acme Store") String name,
		@Schema(example = "billing@acme.example", nullable = true) String email,
		@Schema(example = "+380501234567", nullable = true) String phone,
		@Schema(example = "SUSPENDED") MerchantStatus status) {

	public static MerchantResponse from(Merchant merchant) {
		return new MerchantResponse(merchant.getId(), merchant.getName(), merchant.getEmail(), merchant.getPhone(),
				merchant.getStatus());
	}

}
