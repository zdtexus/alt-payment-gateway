package com.example.payment_service.error;

import java.util.UUID;

public class MerchantNotFoundException extends RuntimeException {

	public MerchantNotFoundException(UUID merchantId) {
		super("Merchant " + merchantId + " not found");
	}

}
