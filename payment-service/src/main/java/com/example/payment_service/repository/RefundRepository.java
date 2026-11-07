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

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM Refund r WHERE r.transaction.provider = :provider AND r.providerReference = :reference")
	Optional<Refund> findLockedByProviderAndProviderReference(@Param("provider") String provider,
			@Param("reference") String reference);

	@Query(value = """
			SELECT r.* FROM refunds r JOIN transactions t ON t.id = r.transaction_id
			WHERE r.status = 'PENDING' AND r.next_check_at <= :now AND t.provider IN (:providers)
			ORDER BY r.next_check_at
			LIMIT :limit
			FOR UPDATE OF r SKIP LOCKED
			""", nativeQuery = true)
	List<Refund> findDueForStatusCheck(@Param("now") java.time.Instant now,
			@Param("providers") Collection<String> providers, @Param("limit") int limit);

	Optional<Refund> findByIdAndTransactionId(UUID id, UUID transactionId);

	@Query("""
			SELECT r.transaction.id AS paymentId, SUM(r.amount) AS amount FROM Refund r
			WHERE r.transaction.id IN :transactionIds AND r.status = :status
			GROUP BY r.transaction.id
			""")
	List<RefundTotal> sumAmountByPayment(@Param("transactionIds") Collection<UUID> transactionIds,
			@Param("status") RefundStatus status);

	@Query("""
			SELECT COALESCE(SUM(r.amount), 0) FROM Refund r
			WHERE r.transaction.id = :transactionId AND r.status IN :statuses
			""")
	BigDecimal sumAmount(@Param("transactionId") UUID transactionId,
			@Param("statuses") Collection<RefundStatus> statuses);

}
