package com.example.payment_service.provider;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.payment_service.entity.PaymentMethod;

public record ProviderRequest(UUID paymentId, PaymentMethod method, String currency, BigDecimal amount) {
}
