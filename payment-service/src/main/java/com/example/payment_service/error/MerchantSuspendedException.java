package com.example.payment_service.error;

public class MerchantSuspendedException extends RuntimeException {

	public MerchantSuspendedException() {
		super("Merchant account is suspended");
	}

}
