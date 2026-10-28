package com.example.payment_service.provider;

public final class HttpPaymentProviderTestSupport {

	private HttpPaymentProviderTestSupport() {
	}

	public static String sign(String secret, byte[] body) {
		return HttpPaymentProvider.sign(secret, body);
	}

}
