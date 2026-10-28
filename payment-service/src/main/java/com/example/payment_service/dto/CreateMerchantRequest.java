package com.example.payment_service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateMerchantRequest(@Schema(example = "Acme Store") @NotBlank @Size(max = 255) String name,
		@Schema(description = "Receives payment receipts by email; optional", example = "billing@acme.example",
				nullable = true) @Email @Pattern(regexp = ".*\\S.*",
						message = "must not be blank") @Size(max = 255) String email,
		@Schema(description = "Receives payment receipts by SMS, in E.164 format; optional",
				example = "+380501234567", nullable = true) @Pattern(regexp = "\\+[1-9][0-9]{6,14}",
						message = "must be in E.164 format, for example +380501234567") String phone) {
}
