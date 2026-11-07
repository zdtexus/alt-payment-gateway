package com.example.payment_service.provider;

public interface AuthorizationStatusCheckingProvider extends StatusCheckingProvider, AuthorizingProvider {

	AuthorizationResult checkAuthorizationStatus(ProviderStatusRequest request);

	ProviderResult checkActionStatus(ActionStatusRequest request);

}
