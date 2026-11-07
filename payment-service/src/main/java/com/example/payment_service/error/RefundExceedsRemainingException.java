package com.example.payment_service.error;

import java.math.BigDecimal;

public class RefundExceedsRemainingException extends RuntimeException {

	public RefundExceedsRemainingException(BigDecimal requested, BigDecimal remaining) {
		super((remaining.signum() == 0) ? "Payment is already fully refunded"
				: "Refund " + requested.toPlainString() + " exceeds the remaining refundable amount "
						+ remaining.toPlainString());
	}

}
