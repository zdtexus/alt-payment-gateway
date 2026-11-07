package com.example.payment_service.service;

import com.example.payment_service.dto.RefundResponse;

public record RefundOutcome(RefundResponse refund, boolean replayed) {
}
