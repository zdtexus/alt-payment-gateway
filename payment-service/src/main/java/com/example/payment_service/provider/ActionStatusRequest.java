package com.example.payment_service.provider;

import java.util.UUID;

import com.example.payment_service.entity.PendingAction;

public record ActionStatusRequest(UUID paymentId, String authorizationReference, PendingAction action) {

	public ActionStatusRequest {
		if (paymentId == null || action == null) {
			throw new IllegalArgumentException("paymentId and action are required");
		}
	}

}
