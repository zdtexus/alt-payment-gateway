package com.example.payment_service.provider;

public interface AuthorizingProvider {

	boolean supportsManualCapture();

	AuthorizationResult authorize(ProviderRequest request);

	ProviderResult capture(CaptureRequest request);

	ProviderResult voidAuthorization(VoidRequest request);

}
