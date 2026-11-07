package com.example.payment_service.provider;

public interface StatusCheckingProvider {

	boolean checksStatus();

	ProviderResult checkStatus(ProviderStatusRequest request);

}
