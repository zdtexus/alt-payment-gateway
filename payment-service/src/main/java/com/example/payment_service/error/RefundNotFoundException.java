package com.example.payment_service.error;

import java.util.UUID;

public class RefundNotFoundException extends RuntimeException {

	public RefundNotFoundException(UUID refundId) {
		super("Refund " + refundId + " not found");
	}

}
