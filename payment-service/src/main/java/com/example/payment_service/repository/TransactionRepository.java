package com.example.payment_service.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

	Optional<Transaction> findByIdAndMerchantId(UUID id, UUID merchantId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Transaction> findLockedById(UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Transaction> findLockedByProviderAndProviderReference(String provider, String providerReference);

	Optional<Transaction> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

	Page<Transaction> findByMerchantId(UUID merchantId, Pageable pageable);

	Page<Transaction> findByMerchantIdAndStatus(UUID merchantId, TransactionStatus status, Pageable pageable);

}
