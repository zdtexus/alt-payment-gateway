package com.example.payment_service.provider;

public interface RefundingProvider {

	boolean supportsRefunds();

	ProviderResult refund(RefundRequest request);

}
