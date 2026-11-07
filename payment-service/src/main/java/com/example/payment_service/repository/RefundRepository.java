package com.example.payment_service.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.payment_service.entity.Refund;
import com.example.payment_service.entity.RefundStatus;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

	List<Refund> findByTransactionIdOrderByCreatedAtAscIdAsc(UUID transactionId);

	Optional<Refund> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Refund> findLockedById(UUID id);

	@Query("""
			SELECT COALESCE(SUM(r.amount), 0) FROM Refund r
			WHERE r.transaction.id = :transactionId AND r.status IN :statuses
			""")
	BigDecimal sumAmount(@Param("transactionId") UUID transactionId,
			@Param("statuses") Collection<RefundStatus> statuses);

}
