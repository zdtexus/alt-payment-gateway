package com.example.payment_service.entity;

import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "merchants")
@Getter
@Setter
@NoArgsConstructor
public class Merchant {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private String name;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "api_key_hash", nullable = false, unique = true, length = 64)
	private String apiKeyHash;

	private String email;

	@Column(length = 32)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private MerchantStatus status;

}
