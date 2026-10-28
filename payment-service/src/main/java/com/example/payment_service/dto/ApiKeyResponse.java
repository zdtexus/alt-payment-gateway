package com.example.payment_service.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record ApiKeyResponse(@Schema(example = "3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f") UUID merchantId,
		@Schema(description = "The merchant's new API key. It is shown only once and cannot be retrieved later; "
				+ "the previous key stopped working",
				example = "q3Zb8kV1xYwP0nR7sT2uL5mH9cJ4dF6gA1eB3iK8oQ0") String apiKey) {

	@Override
	public String toString() {
		return "ApiKeyResponse[merchantId=" + merchantId + ", apiKey=***]";
	}

}
