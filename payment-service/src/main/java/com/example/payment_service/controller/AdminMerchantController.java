package com.example.payment_service.controller;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment_service.config.OpenApiConfig;
import com.example.payment_service.dto.ApiKeyResponse;
import com.example.payment_service.dto.CreateMerchantRequest;
import com.example.payment_service.dto.CreatedMerchantResponse;
import com.example.payment_service.dto.MerchantResponse;
import com.example.payment_service.dto.UpdateMerchantStatusRequest;
import com.example.payment_service.error.ErrorResponse;
import com.example.payment_service.service.MerchantAdminService;

@RestController
@RequestMapping("/admin/v1/merchants")
@Tag(name = "Merchant administration",
		description = "Create merchants, suspend or reactivate them and rotate their API keys")
@SecurityRequirement(name = OpenApiConfig.ADMIN_KEY_SCHEME)
@ApiResponse(responseCode = "401", description = "Missing or invalid admin key",
		content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
				{"timestamp": "2026-01-15T10:30:00Z", "status": 401, "error": "INVALID_ADMIN_KEY",
				 "message": "Missing or invalid admin key", "path": "/admin/v1/merchants"}""")))
public class AdminMerchantController {

	private static final String NO_STORE_DESCRIPTION = "no-store, because the body contains an API key";

	private final MerchantAdminService merchantAdminService;

	public AdminMerchantController(MerchantAdminService merchantAdminService) {
		this.merchantAdminService = merchantAdminService;
	}

	@PostMapping
	@Operation(summary = "Create a merchant",
			description = "Creates an ACTIVE merchant with a random API key. The key is returned only in this "
					+ "response and only its hash is stored, so save it now; a lost key can only be replaced "
					+ "by rotating it.")
	@ApiResponse(responseCode = "201", description = "Merchant created",
			headers = @Header(name = "Cache-Control", description = NO_STORE_DESCRIPTION,
					schema = @Schema(example = "no-store")),
			useReturnTypeSchema = true)
	@ApiResponse(responseCode = "400", description = "Invalid or malformed request body",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-15T10:30:00Z", "status": 400, "error": "VALIDATION_FAILED",
					 "message": "phone: must be in E.164 format, for example +380501234567",
					 "path": "/admin/v1/merchants"}""")))
	public ResponseEntity<CreatedMerchantResponse> create(@Valid @RequestBody CreateMerchantRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(merchantAdminService.create(request));
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Suspend or reactivate a merchant",
			description = "A SUSPENDED merchant's API key gets 403 MERCHANT_SUSPENDED from its next request; "
					+ "ACTIVE lets it work again. Setting the current status again changes nothing.")
	@ApiResponse(responseCode = "200", description = "The merchant with its new status", useReturnTypeSchema = true)
	@ApiResponse(responseCode = "400", description = "Missing or unknown status, or the id is not a valid UUID",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-15T10:30:00Z", "status": 400, "error": "VALIDATION_FAILED",
					 "message": "status: must not be null",
					 "path": "/admin/v1/merchants/3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f"}""")))
	@ApiResponse(responseCode = "404", description = "No such merchant", content = @Content(
			schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-15T10:30:00Z", "status": 404, "error": "MERCHANT_NOT_FOUND",
					 "message": "Merchant 3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f not found",
					 "path": "/admin/v1/merchants/3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f"}""")))
	public MerchantResponse updateStatus(
			@Parameter(description = "Merchant id", example = "3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f") @PathVariable UUID id,
			@Valid @RequestBody UpdateMerchantStatusRequest request) {
		return merchantAdminService.updateStatus(id, request.status());
	}

	@PostMapping("/{id}/api-key")
	@Operation(summary = "Rotate a merchant's API key",
			description = "Replaces the merchant's API key. The old key stops working immediately and the new key "
					+ "is returned only in this response. The merchant's status does not change.")
	@ApiResponse(responseCode = "200", description = "The new API key",
			headers = @Header(name = "Cache-Control", description = NO_STORE_DESCRIPTION,
					schema = @Schema(example = "no-store")),
			useReturnTypeSchema = true)
	@ApiResponse(responseCode = "400", description = "The id is not a valid UUID",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-15T10:30:00Z", "status": 400, "error": "VALIDATION_FAILED",
					 "message": "id: invalid value", "path": "/admin/v1/merchants/abc/api-key"}""")))
	@ApiResponse(responseCode = "404", description = "No such merchant", content = @Content(
			schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(value = """
					{"timestamp": "2026-01-15T10:30:00Z", "status": 404, "error": "MERCHANT_NOT_FOUND",
					 "message": "Merchant 3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f not found",
					 "path": "/admin/v1/merchants/3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f/api-key"}""")))
	public ResponseEntity<ApiKeyResponse> rotateApiKey(
			@Parameter(description = "Merchant id", example = "3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f") @PathVariable UUID id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(merchantAdminService.rotateApiKey(id));
	}

}
