package com.example.payment_service.error;

import java.util.UUID;

import com.example.payment_service.entity.TransactionStatus;

public class PaymentNotRefundableException extends RuntimeException {

	public PaymentNotRefundableException(UUID paymentId, TransactionStatus status) {
		super("Payment " + paymentId + " is " + status + "; "
				+ ((status == TransactionStatus.AUTHORIZED)
						? "capture it before refunding it, or void it to release the reserved amount"
						: "only SUCCEEDED payments can be refunded"));
	}

}
