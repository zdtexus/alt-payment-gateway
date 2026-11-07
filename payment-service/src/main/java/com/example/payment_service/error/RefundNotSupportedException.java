package com.example.payment_service.error;

public class RefundNotSupportedException extends RuntimeException {

	public RefundNotSupportedException() {
		super("This payment can't be refunded through its payment provider");
	}

}
