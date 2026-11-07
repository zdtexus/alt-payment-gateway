package com.example.payment_service.provider;

public interface RefundStatusCheckingProvider extends StatusCheckingProvider {

	ProviderResult checkRefundStatus(RefundStatusRequest request);

}
