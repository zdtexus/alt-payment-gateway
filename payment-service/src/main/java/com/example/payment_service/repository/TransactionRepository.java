package com.example.payment_service.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

	Optional<Transaction> findByIdAndMerchantId(UUID id, UUID merchantId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Transaction> findLockedById(UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Transaction> findLockedByIdAndMerchantId(UUID id, UUID merchantId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Transaction> findLockedByProviderAndProviderReference(String provider, String providerReference);

	Optional<Transaction> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

	Page<Transaction> findByMerchantId(UUID merchantId, Pageable pageable);

	Page<Transaction> findByMerchantIdAndStatus(UUID merchantId, TransactionStatus status, Pageable pageable);

	@Query(value = """
			SELECT * FROM transactions
			WHERE status = 'PENDING' AND next_check_at <= :now AND provider IN (:providers)
			ORDER BY next_check_at
			LIMIT :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Transaction> findDueForStatusCheck(@Param("now") Instant now, @Param("providers") Collection<String> providers,
			@Param("limit") int limit);

	@Query(value = """
			SELECT * FROM transactions
			WHERE status = 'AUTHORIZED' AND pending_action IS NOT NULL AND next_check_at <= :now
			  AND provider IN (:providers)
			ORDER BY next_check_at
			LIMIT :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Transaction> findDueActionChecks(@Param("now") Instant now, @Param("providers") Collection<String> providers,
			@Param("limit") int limit);

	@Query(value = """
			SELECT * FROM transactions
			WHERE status = 'AUTHORIZED' AND pending_action IS NULL AND authorization_expires_at <= :now
			  AND (next_check_at IS NULL OR next_check_at <= :now) AND provider IN (:providers)
			ORDER BY authorization_expires_at
			LIMIT :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Transaction> findExpiredAuthorizations(@Param("now") Instant now,
			@Param("providers") Collection<String> providers, @Param("limit") int limit);

}
