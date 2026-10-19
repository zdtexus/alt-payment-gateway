package com.example.payment_service.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.payment_service.entity.Merchant;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

	Optional<Merchant> findByApiKey(String apiKey);

}
