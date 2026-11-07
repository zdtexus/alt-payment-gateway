package com.example.payment_service.error;

import com.example.payment_service.entity.PaymentMethod;

public class ManualCaptureNotSupportedException extends RuntimeException {

	public ManualCaptureNotSupportedException(PaymentMethod method) {
		super("Manual capture is not supported for " + method + " payments");
	}

}
