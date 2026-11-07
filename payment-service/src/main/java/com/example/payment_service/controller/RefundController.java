package com.example.payment_service.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.payment_service.auth.CurrentMerchant;
import com.example.payment_service.dto.CreateRefundRequest;
import com.example.payment_service.dto.RefundResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.error.ErrorResponse;
import com.example.payment_service.service.RefundOutcome;
import com.example.payment_service.service.RefundService;

@RestController
@RequestMapping("/api/v1/payments/{paymentId}/refunds")
@Tag(name = "Refunds", description = "Refund a succeeded payment in full or in parts, and look up its refunds")
@ApiResponse(responseCode = "401", description = "Missing or unknown API key",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
				{"timestamp": "2026-01-16T09:00:00Z", "status": 401, "error": "INVALID_API_KEY",
				 "message": "Missing or unknown API key",
				 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds"}""")))
@ApiResponse(responseCode = "403", description = "Merchant account is suspended",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
				{"timestamp": "2026-01-16T09:00:00Z", "status": 403, "error": "MERCHANT_SUSPENDED",
				 "message": "Merchant account is suspended",
				 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds"}""")))
@ApiResponse(responseCode = "404", description = "No such payment or refund, or the payment belongs to another merchant",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = {
				@ExampleObject(name = "PAYMENT_NOT_FOUND", value = """
						{"timestamp": "2026-01-16T09:00:00Z", "status": 404, "error": "PAYMENT_NOT_FOUND",
						 "message": "Payment 7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b not found",
						 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds"}"""),
				@ExampleObject(name = "REFUND_NOT_FOUND", value = """
						{"timestamp": "2026-01-16T09:00:00Z", "status": 404, "error": "REFUND_NOT_FOUND",
						 "message": "Refund 0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d not found",
						 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds/0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"}""") }))
public class RefundController {

	private final RefundService refundService;

	public RefundController(RefundService refundService) {
		this.refundService = refundService;
	}

	@PostMapping
	@Operation(summary = "Refund a payment",
			description = "Refunds a SUCCEEDED payment, in full or in parts. Without an amount, everything that is "
					+ "still refundable is refunded. Refunds of one payment never add up to more than its amount: "
					+ "SUCCEEDED and PENDING refunds both count. The payment keeps its SUCCEEDED status and shows "
					+ "the refunded sum as refundedAmount. A declined refund is still created (201) with status "
					+ "DECLINED; a refund whose result is not known yet is created with status PENDING. "
					+ "Send an Idempotency-Key to make retries safe; keys for refunds are separate from keys for "
					+ "payments.")
	@ApiResponse(responseCode = "201", description = "Refund created (SUCCEEDED, DECLINED or PENDING), or replayed",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = RefundResponse.class),
					examples = {
							@ExampleObject(name = "SUCCEEDED", value = """
									{"id": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
									 "paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "amount": 20.00,
									 "currency": "EUR", "status": "SUCCEEDED", "reason": "Item returned",
									 "declineReason": null, "createdAt": "2026-01-16T09:00:00Z",
									 "processedAt": "2026-01-16T09:00:00.300Z"}"""),
							@ExampleObject(name = "DECLINED", value = """
									{"id": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
									 "paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "amount": 20.00,
									 "currency": "EUR", "status": "DECLINED", "reason": null,
									 "declineReason": "Card account closed", "createdAt": "2026-01-16T09:00:00Z",
									 "processedAt": "2026-01-16T09:00:00.300Z"}""") }),
			headers = {
					@Header(name = "Location", description = "Relative URL of the created refund",
							schema = @Schema(example = "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds/0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")),
					@Header(name = PaymentController.IDEMPOTENT_REPLAYED_HEADER,
							description = "Present and true when the response is a replay of an earlier request "
									+ "with the same Idempotency-Key",
							schema = @Schema(type = "boolean", example = "true")) })
	@ApiResponse(responseCode = "400", description = "Invalid or malformed request body",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-16T09:00:00Z", "status": 400, "error": "VALIDATION_FAILED",
					 "message": "amount: must be greater than 0",
					 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds"}""")))
	@ApiResponse(responseCode = "409", description = "The payment is not SUCCEEDED",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-16T09:00:00Z", "status": 409, "error": "PAYMENT_NOT_REFUNDABLE",
					 "message": "Payment 7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b is DECLINED; only SUCCEEDED payments can be refunded",
					 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds"}""")))
	@ApiResponse(responseCode = "422",
			description = "More than the remaining refundable amount, a payment provider without refunds, "
					+ "or the Idempotency-Key was already used with a different body",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = {
					@ExampleObject(name = "REFUND_EXCEEDS_REMAINING", value = """
							{"timestamp": "2026-01-16T09:00:00Z", "status": 422, "error": "REFUND_EXCEEDS_REMAINING",
							 "message": "Refund 30.00 exceeds the remaining refundable amount 20.00",
							 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds"}"""),
					@ExampleObject(name = "REFUND_NOT_SUPPORTED", value = """
							{"timestamp": "2026-01-16T09:00:00Z", "status": 422, "error": "REFUND_NOT_SUPPORTED",
							 "message": "This payment can't be refunded through its payment provider",
							 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds"}"""),
					@ExampleObject(name = "IDEMPOTENCY_KEY_REUSED", value = """
							{"timestamp": "2026-01-16T09:00:00Z", "status": 422, "error": "IDEMPOTENCY_KEY_REUSED",
							 "message": "Idempotency key was already used with a different request",
							 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds"}""") }))
	public ResponseEntity<RefundResponse> create(@CurrentMerchant Merchant merchant,
			@Parameter(description = "Payment id", example = "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b") @PathVariable UUID paymentId,
			@Valid @RequestBody(required = false) CreateRefundRequest request,
			@Parameter(description = "Unique key for this refund, 1 to 255 characters. Keys are scoped to the "
					+ "merchant and separate from payment keys.", example = "return-1001") @RequestHeader(
							name = PaymentController.IDEMPOTENCY_KEY_HEADER,
							required = false) @Size(min = 1, max = 255) String idempotencyKey) {
		CreateRefundRequest body = (request != null) ? request : new CreateRefundRequest(null, null);
		RefundOutcome outcome = refundService.create(merchant, paymentId, body.amount(), body.reason(), idempotencyKey);
		RefundResponse refund = outcome.refund();
		URI location = UriComponentsBuilder.fromPath("/api/v1/payments/{paymentId}/refunds/{id}")
			.buildAndExpand(refund.paymentId(), refund.id())
			.toUri();
		ResponseEntity.BodyBuilder builder = ResponseEntity.created(location);
		if (outcome.replayed()) {
			builder.header(PaymentController.IDEMPOTENT_REPLAYED_HEADER, "true");
		}
		return builder.body(refund);
	}

	@GetMapping
	@Operation(summary = "List a payment's refunds", description = "Returns all refunds of one of the authenticated "
			+ "merchant's payments, oldest first.")
	@ApiResponse(responseCode = "200", description = "The payment's refunds, possibly none",
			content = @Content(mediaType = "application/json",
					array = @ArraySchema(schema = @Schema(implementation = RefundResponse.class))))
	public List<RefundResponse> list(@CurrentMerchant Merchant merchant,
			@Parameter(description = "Payment id", example = "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b") @PathVariable UUID paymentId) {
		return refundService.list(merchant, paymentId);
	}

	@GetMapping("/{refundId}")
	@Operation(summary = "Get a refund", description = "Returns one refund of one of the authenticated merchant's "
			+ "payments. Poll it to see a PENDING refund become SUCCEEDED or DECLINED.")
	@ApiResponse(responseCode = "200", description = "The refund", useReturnTypeSchema = true)
	public RefundResponse get(@CurrentMerchant Merchant merchant,
			@Parameter(description = "Payment id", example = "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b") @PathVariable UUID paymentId,
			@Parameter(description = "Refund id", example = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d") @PathVariable UUID refundId) {
		return refundService.get(merchant, paymentId, refundId);
	}

}
