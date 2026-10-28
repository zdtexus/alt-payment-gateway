package com.example.payment_service.controller;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment_service.dto.CreateMerchantRequest;
import com.example.payment_service.dto.CreatedMerchantResponse;
import com.example.payment_service.service.MerchantAdminService;

@RestController
@RequestMapping("/admin/v1/merchants")
public class AdminMerchantController {

	private final MerchantAdminService merchantAdminService;

	public AdminMerchantController(MerchantAdminService merchantAdminService) {
		this.merchantAdminService = merchantAdminService;
	}

	@PostMapping
	public ResponseEntity<CreatedMerchantResponse> create(@Valid @RequestBody CreateMerchantRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(merchantAdminService.create(request));
	}

}
