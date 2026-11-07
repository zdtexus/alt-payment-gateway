package com.example.payment_service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment_service.error.ErrorResponse;
import com.example.payment_service.error.WebhookNotAcceptedException;
import com.example.payment_service.provider.PaymentProviderRegistry;
import com.example.payment_service.provider.ProviderWebhook;
import com.example.payment_service.provider.WebhookProvider;
import com.example.payment_service.service.CaptureService;
import com.example.payment_service.service.PaymentService;
import com.example.payment_service.service.RefundService;

@RestController
@RequestMapping("/api/v1/providers")
@Tag(name = "Provider webhooks",
		description = "Final results that payment providers report after a payment, refund, authorization, capture or "
				+ "void was left pending")
public class ProviderWebhookController {

	public static final String SIGNATURE_HEADER = "X-Webhook-Signature";

	private final PaymentProviderRegistry providerRegistry;

	private final PaymentService paymentService;

	private final RefundService refundService;

	private final CaptureService captureService;

	public ProviderWebhookController(PaymentProviderRegistry providerRegistry, PaymentService paymentService,
			RefundService refundService, CaptureService captureService) {
		this.providerRegistry = providerRegistry;
		this.paymentService = paymentService;
		this.refundService = refundService;
		this.captureService = captureService;
	}

	@PostMapping(path = "/{name}/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
	@SecurityRequirements
	@Operation(summary = "Receive a payment provider's webhook",
			description = "Called by a payment provider, not by merchants; authenticated by the signature instead of "
					+ "an API key. The payment is found by the provider's reference (or by paymentId while it has no "
					+ "reference yet) and moves from PENDING to SUCCEEDED or DECLINED once; the receipt is sent then. "
					+ "A webhook for a payment that is already final changes nothing and still returns 204. "
					+ "Refund results use the same endpoint and signature with \"type\": \"refund\": the refund "
					+ "is found by the provider's reference (or by refundId while it has no reference yet). "
					+ "Manual capture uses \"type\": \"authorization\" (with expiresAt when approved), "
					+ "\"capture\" and \"void\" (with paymentId).")
	@ApiResponse(responseCode = "204", description = "Webhook accepted (applied, or already applied)")
	@ApiResponse(responseCode = "400", description = "Body is not a valid webhook",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "401", description = "Missing or invalid signature",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class),
					examples = @ExampleObject(value = """
							{"timestamp": "2026-01-15T10:30:00Z", "status": 401, "error": "INVALID_WEBHOOK_SIGNATURE",
							 "message": "Missing or invalid webhook signature", "path": "/api/v1/providers/http/webhook"}""")))
	@ApiResponse(responseCode = "404", description = "Provider doesn't accept webhooks, or no payment or refund "
			+ "matches yet (the provider should retry)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public ResponseEntity<Void> receive(@PathVariable String name,
			@Parameter(description = "sha256=<hex HMAC-SHA256 of the raw body with the provider's webhook secret>")
			@RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					content = @Content(examples = {
							@ExampleObject(name = "payment", value = """
									{"paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "status": "APPROVED",
									 "reference": "acme_123"}"""),
							@ExampleObject(name = "refund", value = """
									{"type": "refund", "refundId": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
									 "status": "APPROVED", "reference": "acme_r_7"}"""),
							@ExampleObject(name = "authorization", value = """
									{"type": "authorization", "paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b",
									 "status": "APPROVED", "reference": "acme_a_1", "expiresAt": "2026-01-22T10:30:00Z"}"""),
							@ExampleObject(name = "capture", value = """
									{"type": "capture", "paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b",
									 "status": "APPROVED", "reference": "acme_c_1"}"""),
							@ExampleObject(name = "void", value = """
									{"type": "void", "paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b",
									 "status": "APPROVED", "reference": "acme_v_1"}""") })) @RequestBody byte[] body) {
		WebhookProvider provider = providerRegistry.findWebhookProvider(name)
			.orElseThrow(() -> new WebhookNotAcceptedException(name));
		ProviderWebhook webhook = provider.readWebhook(body, signature);
		switch (webhook.type()) {
			case PAYMENT -> paymentService.settleFromWebhook(name, webhook);
			case REFUND -> refundService.settleFromWebhook(name, webhook);
			case AUTHORIZATION -> paymentService.settleAuthorizationFromWebhook(name, webhook);
			case CAPTURE, VOID -> captureService.settleFromWebhook(name, webhook);
		}
		return ResponseEntity.noContent().build();
	}

}
