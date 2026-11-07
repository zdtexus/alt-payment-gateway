package com.example.payment_service.repository;

import java.math.BigDecimal;
import java.util.UUID;

public interface RefundTotal {

	UUID getPaymentId();

	BigDecimal getAmount();

}
