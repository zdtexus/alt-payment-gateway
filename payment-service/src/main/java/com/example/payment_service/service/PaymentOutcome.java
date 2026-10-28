package com.example.payment_service.service;

import com.example.payment_service.dto.PaymentResponse;

public record PaymentOutcome(PaymentResponse payment, boolean replayed) {
}
