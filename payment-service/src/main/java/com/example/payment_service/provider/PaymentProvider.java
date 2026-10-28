package com.example.payment_service.provider;

public interface PaymentProvider {

	String name();

	ProviderResult process(ProviderRequest request);

}
