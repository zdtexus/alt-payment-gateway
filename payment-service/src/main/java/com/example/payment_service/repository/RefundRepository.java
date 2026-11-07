package com.example.payment_service.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.payment_service.entity.Refund;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

	List<Refund> findByTransactionIdOrderByCreatedAtAscIdAsc(UUID transactionId);

	Optional<Refund> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

}
