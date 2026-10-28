package com.example.payment_service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import com.example.payment_service.entity.MerchantStatus;

public record UpdateMerchantStatusRequest(@Schema(description = "SUSPENDED blocks the merchant's API key at once; "
		+ "ACTIVE lets it work again", example = "SUSPENDED") @NotNull MerchantStatus status) {
}
