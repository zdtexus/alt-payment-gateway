package com.example.payment_service.controller;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
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
@ApiResponse(responseCode = "200", description = "Done: the payment is SUCCEEDED (captured) or VOIDED",
		useReturnTypeSchema = true)
@ApiResponse(responseCode = "202",
		description = "The provider's answer is not known yet; the payment stays AUTHORIZED with pendingAction set",
		useReturnTypeSchema = true)
@ApiResponse(responseCode = "404", description = "No such payment, or it belongs to another merchant",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
@ApiResponse(responseCode = "409",
		description = "The payment is not AUTHORIZED, another capture or void is in progress, or the authorization expired",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
@ApiResponse(responseCode = "422",
		description = "The provider declined, the amount exceeds the authorized one, or the Idempotency-Key was used "
				+ "with a different request",
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
					+ "voided once.")
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
					+ "becomes VOIDED, and the merchant gets a receipt.")
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
