package com.example.payment_service.controller;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import com.example.payment_service.error.ErrorResponse;
import com.example.payment_service.service.PaymentService;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Create payments and look up the authenticated merchant's payments")
@ApiResponse(responseCode = "401", description = "Missing or unknown API key",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
				{"timestamp": "2026-01-15T10:30:00Z", "status": 401, "error": "INVALID_API_KEY",
				 "message": "Missing or unknown API key", "path": "/api/v1/payments"}""")))
@ApiResponse(responseCode = "403", description = "Merchant account is suspended",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
				{"timestamp": "2026-01-15T10:30:00Z", "status": 403, "error": "MERCHANT_SUSPENDED",
				 "message": "Merchant account is suspended", "path": "/api/v1/payments"}""")))
public class PaymentController {

	private final PaymentService paymentService;

	public PaymentController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@PostMapping
	@Operation(summary = "Create a payment",
			description = "Validates the payment method rules, sends the payment to the provider and returns the result. "
					+ "A declined payment is still created (201) with status DECLINED and a declineReason. "
					+ "The merchant gets an email and SMS receipt after the payment is stored.")
	@ApiResponse(responseCode = "201", description = "Payment processed (SUCCEEDED or DECLINED)",
			headers = @Header(name = "Location", description = "Relative URL of the created payment",
					schema = @Schema(example = "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b")),
			useReturnTypeSchema = true)
	@ApiResponse(responseCode = "400", description = "Invalid or malformed request body",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-15T10:30:00Z", "status": 400, "error": "VALIDATION_FAILED",
					 "message": "amount: must be greater than 0", "path": "/api/v1/payments"}""")))
	@ApiResponse(responseCode = "422", description = "Amount or currency not allowed for the payment method",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-15T10:30:00Z", "status": 422, "error": "PAYMENT_RULE_VIOLATION",
					 "message": "Amount 600.00 exceeds BONUS limit 500.00", "path": "/api/v1/payments"}""")))
	public ResponseEntity<PaymentResponse> create(@CurrentMerchant Merchant merchant,
			@Valid @RequestBody CreatePaymentRequest request) {
		PaymentResponse response = paymentService.process(merchant, request);
		URI location = UriComponentsBuilder.fromPath("/api/v1/payments/{id}").buildAndExpand(response.id()).toUri();
		return ResponseEntity.created(location).body(response);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get a payment", description = "Returns one of the authenticated merchant's payments.")
	@ApiResponse(responseCode = "200", description = "The payment", useReturnTypeSchema = true)
	@ApiResponse(responseCode = "400", description = "The id is not a valid UUID",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-15T10:30:00Z", "status": 400, "error": "VALIDATION_FAILED",
					 "message": "id: invalid value", "path": "/api/v1/payments/abc"}""")))
	@ApiResponse(responseCode = "404", description = "No such payment, or it belongs to another merchant",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-15T10:30:00Z", "status": 404, "error": "PAYMENT_NOT_FOUND",
					 "message": "Payment 7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b not found",
					 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b"}""")))
	public PaymentResponse get(@CurrentMerchant Merchant merchant,
			@Parameter(description = "Payment id", example = "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b") @PathVariable UUID id) {
		return paymentService.get(merchant, id);
	}

	@GetMapping
	@Operation(summary = "List payments",
			description = "Returns the authenticated merchant's payments, newest first, optionally filtered by status.")
	@ApiResponse(responseCode = "200", description = "One page of payments", useReturnTypeSchema = true)
	@ApiResponse(responseCode = "400", description = "Invalid status, page or size",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-15T10:30:00Z", "status": 400, "error": "VALIDATION_FAILED",
					 "message": "size: must be less than or equal to 100", "path": "/api/v1/payments"}""")))
	public PageResponse<PaymentResponse> list(@CurrentMerchant Merchant merchant,
			@Parameter(description = "Only payments with this status") @RequestParam(required = false) TransactionStatus status,
			@Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @PositiveOrZero int page,
			@Parameter(description = "Page size, 1 to 100") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return paymentService.list(merchant, status, page, size);
	}

}
