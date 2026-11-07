package com.example.payment_service.controller;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment_service.auth.CurrentMerchant;
import com.example.payment_service.dto.CapturePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.error.ErrorResponse;
import com.example.payment_service.service.CaptureService;
import com.example.payment_service.service.PaymentOutcome;

@RestController
@RequestMapping("/api/v1/payments/{paymentId}")
@Tag(name = "Payments")
@ApiResponse(responseCode = "400", description = "Invalid payment id, amount or Idempotency-Key",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
@ApiResponse(responseCode = "401", description = "Missing or unknown API key",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
@ApiResponse(responseCode = "403", description = "Merchant account is suspended",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
@ApiResponse(responseCode = "404", description = "No such payment, or it belongs to another merchant",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
public class CaptureController {

	private final CaptureService captureService;

	public CaptureController(CaptureService captureService) {
		this.captureService = captureService;
	}

	@PostMapping("/capture")
	@Operation(summary = "Capture an authorized payment",
			description = "Charges all or part of the amount reserved by a MANUAL payment. The payment becomes "
					+ "SUCCEEDED with capturedAmount, and the merchant gets a receipt. A payment is captured or "
					+ "voided once. A declined capture changes nothing: the payment stays AUTHORIZED.")
	@ApiResponse(responseCode = "200", description = "Captured: the payment is SUCCEEDED", content = @Content(
			mediaType = "application/json", schema = @Schema(implementation = PaymentResponse.class),
			examples = @ExampleObject(name = "SUCCEEDED", value = """
					{"id": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "amount": 49.90, "refundedAmount": 0.00, "currency": "EUR",
					 "paymentMethod": "CARD", "captureMethod": "MANUAL", "status": "SUCCEEDED", "declineReason": null,
					 "createdAt": "2026-01-15T10:30:00Z", "processedAt": "2026-01-16T09:00:00.300Z",
					 "authorizationExpiresAt": "2026-01-22T10:30:00.300Z", "capturedAmount": 30.00, "pendingAction": null}""")))
	@ApiResponse(responseCode = "202",
			description = "The provider's answer is not known yet; the payment stays AUTHORIZED with pendingAction "
					+ "CAPTURE until its webhook or a status check settles it",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = PaymentResponse.class),
					examples = @ExampleObject(name = "PENDING_CAPTURE", value = """
							{"id": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "amount": 49.90, "refundedAmount": 0.00, "currency": "EUR",
							 "paymentMethod": "CARD", "captureMethod": "MANUAL", "status": "AUTHORIZED", "declineReason": null,
							 "createdAt": "2026-01-15T10:30:00Z", "processedAt": null,
							 "authorizationExpiresAt": "2026-01-22T10:30:00.300Z", "capturedAmount": null, "pendingAction": "CAPTURE"}""")))
	@ApiResponse(responseCode = "409",
			description = "The payment isn't AUTHORIZED, a capture or void is in progress, or the authorization expired",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(
					name = "PAYMENT_NOT_CAPTURABLE", value = """
							{"timestamp": "2026-01-16T09:00:00Z", "status": 409, "error": "PAYMENT_NOT_CAPTURABLE",
							 "message": "Payment 7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b is VOIDED; only AUTHORIZED payments can be captured",
							 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/capture"}""")))
	@ApiResponse(responseCode = "422",
			description = "More than the authorized amount, declined by the provider, the provider no longer supports "
					+ "manual capture, or the Idempotency-Key was used with a different request",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = {
					@ExampleObject(name = "CAPTURE_EXCEEDS_AUTHORIZED", value = """
							{"timestamp": "2026-01-16T09:00:00Z", "status": 422, "error": "CAPTURE_EXCEEDS_AUTHORIZED",
							 "message": "Capture 60.00 exceeds the authorized amount 49.90",
							 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/capture"}"""),
					@ExampleObject(name = "CAPTURE_DECLINED", value = """
							{"timestamp": "2026-01-16T09:00:00Z", "status": 422, "error": "CAPTURE_DECLINED",
							 "message": "The payment provider declined the capture: Authorization cancelled. The payment stays AUTHORIZED",
							 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/capture"}""") }))
	public ResponseEntity<PaymentResponse> capture(@CurrentMerchant Merchant merchant,
			@Parameter(description = "Payment id", example = "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b") @PathVariable UUID paymentId,
			@Valid @RequestBody(required = false) CapturePaymentRequest request,
			@Parameter(description = "Unique key for this capture, 1 to 255 characters",
					example = "ship-1001") @RequestHeader(name = PaymentController.IDEMPOTENCY_KEY_HEADER,
							required = false) @Size(min = 1, max = 255) String idempotencyKey) {
		return respond(captureService.capture(merchant, paymentId, (request != null) ? request.amount() : null,
				idempotencyKey));
	}

	@PostMapping("/void")
	@Operation(summary = "Void an authorized payment",
			description = "Releases the amount reserved by a MANUAL payment without charging it. The payment "
					+ "becomes VOIDED, and the merchant gets a receipt. An expired authorization can still be voided.")
	@ApiResponse(responseCode = "200", description = "Voided: the payment is VOIDED", content = @Content(
			mediaType = "application/json", schema = @Schema(implementation = PaymentResponse.class),
			examples = @ExampleObject(name = "VOIDED", value = """
					{"id": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "amount": 49.90, "refundedAmount": 0.00, "currency": "EUR",
					 "paymentMethod": "CARD", "captureMethod": "MANUAL", "status": "VOIDED", "declineReason": null,
					 "createdAt": "2026-01-15T10:30:00Z", "processedAt": "2026-01-16T09:00:00.300Z",
					 "authorizationExpiresAt": "2026-01-22T10:30:00.300Z", "capturedAmount": null, "pendingAction": null}""")))
	@ApiResponse(responseCode = "202",
			description = "The provider's answer is not known yet; the payment stays AUTHORIZED with pendingAction VOID",
			useReturnTypeSchema = true)
	@ApiResponse(responseCode = "409", description = "The payment isn't AUTHORIZED, or a capture or void is in progress",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(
					name = "PAYMENT_NOT_VOIDABLE", value = """
							{"timestamp": "2026-01-16T09:00:00Z", "status": 409, "error": "PAYMENT_NOT_VOIDABLE",
							 "message": "Payment 7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b is SUCCEEDED; only AUTHORIZED payments can be voided",
							 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/void"}""")))
	@ApiResponse(responseCode = "422",
			description = "Declined by the provider, or the Idempotency-Key was used with a different request",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(
					name = "VOID_DECLINED", value = """
							{"timestamp": "2026-01-16T09:00:00Z", "status": 422, "error": "VOID_DECLINED",
							 "message": "The payment provider declined the void: Already captured. The payment stays AUTHORIZED",
							 "path": "/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/void"}""")))
	public ResponseEntity<PaymentResponse> voidAuthorization(@CurrentMerchant Merchant merchant,
			@Parameter(description = "Payment id", example = "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b") @PathVariable UUID paymentId,
			@Parameter(description = "Unique key for this void, 1 to 255 characters",
					example = "cancel-1001") @RequestHeader(name = PaymentController.IDEMPOTENCY_KEY_HEADER,
							required = false) @Size(min = 1, max = 255) String idempotencyKey) {
		return respond(captureService.voidAuthorization(merchant, paymentId, idempotencyKey));
	}

	private static ResponseEntity<PaymentResponse> respond(PaymentOutcome outcome) {
		PaymentResponse payment = outcome.payment();
		ResponseEntity.BodyBuilder builder = ResponseEntity
			.status((payment.pendingAction() != null) ? HttpStatus.ACCEPTED : HttpStatus.OK);
		if (outcome.replayed()) {
			builder.header(PaymentController.IDEMPOTENT_REPLAYED_HEADER, "true");
		}
		return builder.body(payment);
	}

}
