package com.example.payment_service.provider;

public interface PaymentProvider {

	ProviderResult process(ProviderRequest request);

}
