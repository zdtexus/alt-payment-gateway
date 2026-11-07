package com.example.payment_service.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
public class Refund {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "transaction_id", nullable = false, updatable = false)
	private Transaction transaction;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "merchant_id", nullable = false, updatable = false)
	private Merchant merchant;

	@Column(nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private RefundStatus status;

	@Column(name = "reason", updatable = false)
	private String reason;

	@Column(name = "provider_reference")
	private String providerReference;

	@Column(name = "decline_reason")
	private String declineReason;

	@Column(name = "idempotency_key", updatable = false)
	private String idempotencyKey;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "processed_at")
	private Instant processedAt;

	@Column(name = "next_check_at")
	private Instant nextCheckAt;

	@Column(name = "check_attempts", nullable = false)
	private int checkAttempts;

	@PrePersist
	void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		}
	}

}
