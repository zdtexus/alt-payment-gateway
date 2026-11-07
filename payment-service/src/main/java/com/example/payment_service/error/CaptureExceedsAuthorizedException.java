package com.example.payment_service.error;

import java.math.BigDecimal;

public class CaptureExceedsAuthorizedException extends RuntimeException {

	public CaptureExceedsAuthorizedException(BigDecimal requested, BigDecimal authorized) {
		super("Capture " + requested.toPlainString() + " exceeds the authorized amount " + authorized.toPlainString());
	}

}
