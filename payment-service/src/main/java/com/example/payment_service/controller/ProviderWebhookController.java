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
import com.example.payment_service.service.PaymentService;

@RestController
@RequestMapping("/api/v1/providers")
@Tag(name = "Provider webhooks", description = "Final results that payment providers report after a payment was left PENDING")
public class ProviderWebhookController {

	public static final String SIGNATURE_HEADER = "X-Webhook-Signature";

	private final PaymentProviderRegistry providerRegistry;

	private final PaymentService paymentService;

	public ProviderWebhookController(PaymentProviderRegistry providerRegistry, PaymentService paymentService) {
		this.providerRegistry = providerRegistry;
		this.paymentService = paymentService;
	}

	@PostMapping(path = "/{name}/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
	@SecurityRequirements
	@Operation(summary = "Receive a payment provider's webhook",
			description = "Called by a payment provider, not by merchants; authenticated by the signature instead of "
					+ "an API key. The payment is found by the provider's reference (or by paymentId while it has no "
					+ "reference yet) and moves from PENDING to SUCCEEDED or DECLINED once; the receipt is sent then. "
					+ "A webhook for a payment that is already final changes nothing and still returns 204.")
	@ApiResponse(responseCode = "204", description = "Webhook accepted (applied, or already applied)")
	@ApiResponse(responseCode = "400", description = "Body is not a valid webhook",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "401", description = "Missing or invalid signature",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class),
					examples = @ExampleObject(value = """
							{"timestamp": "2026-01-15T10:30:00Z", "status": 401, "error": "INVALID_WEBHOOK_SIGNATURE",
							 "message": "Missing or invalid webhook signature", "path": "/api/v1/providers/http/webhook"}""")))
	@ApiResponse(responseCode = "404", description = "Provider doesn't accept webhooks, or no payment matches yet "
			+ "(the provider should retry)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public ResponseEntity<Void> receive(@PathVariable String name,
			@Parameter(description = "sha256=<hex HMAC-SHA256 of the raw body with the provider's webhook secret>")
			@RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					content = @Content(examples = @ExampleObject(value = """
							{"paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "status": "APPROVED",
							 "reference": "acme_123"}"""))) @RequestBody byte[] body) {
		WebhookProvider provider = providerRegistry.findWebhookProvider(name)
			.orElseThrow(() -> new WebhookNotAcceptedException(name));
		ProviderWebhook webhook = provider.readWebhook(body, signature);
		paymentService.settleFromWebhook(name, webhook);
		return ResponseEntity.noContent().build();
	}

}
