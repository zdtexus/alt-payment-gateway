package com.example.payment_service.controller;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.payment_service.auth.CurrentMerchant;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PageResponse;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.service.PaymentService;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

	private final PaymentService paymentService;

	public PaymentController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@PostMapping
	public ResponseEntity<PaymentResponse> create(@CurrentMerchant Merchant merchant,
			@Valid @RequestBody CreatePaymentRequest request) {
		PaymentResponse response = paymentService.process(merchant, request);
		URI location = UriComponentsBuilder.fromPath("/api/v1/payments/{id}").buildAndExpand(response.id()).toUri();
		return ResponseEntity.created(location).body(response);
	}

	@GetMapping("/{id}")
	public PaymentResponse get(@CurrentMerchant Merchant merchant, @PathVariable UUID id) {
		return paymentService.get(merchant, id);
	}

	@GetMapping
	public PageResponse<PaymentResponse> list(@CurrentMerchant Merchant merchant,
			@RequestParam(required = false) TransactionStatus status,
			@RequestParam(defaultValue = "0") @PositiveOrZero int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return paymentService.list(merchant, status, page, size);
	}

}
