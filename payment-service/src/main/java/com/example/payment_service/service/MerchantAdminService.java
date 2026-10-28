package com.example.payment_service.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payment_service.auth.ApiKeyGenerator;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.dto.CreateMerchantRequest;
import com.example.payment_service.dto.CreatedMerchantResponse;
import com.example.payment_service.dto.MerchantResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.error.MerchantNotFoundException;
import com.example.payment_service.repository.MerchantRepository;

@Service
public class MerchantAdminService {

	private final MerchantRepository merchantRepository;

	public MerchantAdminService(MerchantRepository merchantRepository) {
		this.merchantRepository = merchantRepository;
	}

	@Transactional
	public CreatedMerchantResponse create(CreateMerchantRequest request) {
		String apiKey = ApiKeyGenerator.generate();
		Merchant merchant = new Merchant();
		merchant.setName(request.name());
		merchant.setEmail(request.email());
		merchant.setPhone(request.phone());
		merchant.setStatus(MerchantStatus.ACTIVE);
		merchant.setApiKeyHash(ApiKeyHasher.hash(apiKey));
		return CreatedMerchantResponse.from(merchantRepository.save(merchant), apiKey);
	}

	@Transactional
	public MerchantResponse updateStatus(UUID id, MerchantStatus status) {
		Merchant merchant = merchantRepository.findById(id).orElseThrow(() -> new MerchantNotFoundException(id));
		merchant.setStatus(status);
		return MerchantResponse.from(merchant);
	}

}
