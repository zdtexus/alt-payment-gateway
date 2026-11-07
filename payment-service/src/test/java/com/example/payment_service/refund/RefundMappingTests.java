package com.example.payment_service.refund;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Refund;
import com.example.payment_service.entity.RefundStatus;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.RefundRepository;
import com.example.payment_service.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefundMappingTests {

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	private RefundRepository refundRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Merchant merchant;

	private Transaction payment;

	@BeforeEach
	void succeededPayment() {
		merchant = merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow();
		Transaction transaction = new Transaction();
		transaction.setMerchant(merchant);
		transaction.setAmount(new BigDecimal("50.00"));
		transaction.setCurrency("EUR");
		transaction.setPaymentMethod(PaymentMethod.CARD);
		transaction.setStatus(TransactionStatus.SUCCEEDED);
		transaction.setProvider("simulated");
		payment = transactionRepository.saveAndFlush(transaction);
	}

	@Test
	void everyFieldIsStoredAndLoaded() {
		Instant created = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
		Instant due = created.plusSeconds(120);
		Refund refund = refund(new BigDecimal("12.34"), created);
		refund.setReason("Customer returned the item");
		refund.setProviderReference("sim_r_1");
		refund.setIdempotencyKey("refund-key-" + UUID.randomUUID());
		refund.setRequestHash("c".repeat(64));
		refund.setNextCheckAt(due);
		refund.setCheckAttempts(2);
		UUID id = refundRepository.saveAndFlush(refund).getId();

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM refunds WHERE id = ?", id);
		assertThat(row).containsEntry("transaction_id", payment.getId())
			.containsEntry("merchant_id", DEMO_MERCHANT_ID)
			.containsEntry("amount", new BigDecimal("12.34"))
			.containsEntry("status", "PENDING")
			.containsEntry("reason", "Customer returned the item")
			.containsEntry("provider_reference", "sim_r_1")
			.containsEntry("decline_reason", null)
			.containsEntry("request_hash", "c".repeat(64))
			.containsEntry("processed_at", null)
			.containsEntry("check_attempts", 2);
		assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(created);
		assertThat(((Timestamp) row.get("next_check_at")).toInstant()).isEqualTo(due);

		Refund loaded = refundRepository.findById(id).orElseThrow();
		assertThat(loaded.getTransaction().getId()).isEqualTo(payment.getId());
		assertThat(loaded.getMerchant().getId()).isEqualTo(DEMO_MERCHANT_ID);
		assertThat(loaded.getAmount()).isEqualTo(new BigDecimal("12.34"));
		assertThat(loaded.getStatus()).isEqualTo(RefundStatus.PENDING);
		assertThat(loaded.getCreatedAt()).isEqualTo(created);
		assertThat(loaded.getNextCheckAt()).isEqualTo(due);
	}

	@Test
	void creationTimeIsFilledInWhenMissing() {
		Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

		Refund saved = refundRepository.saveAndFlush(refund(new BigDecimal("1.00"), null));

		assertThat(saved.getCreatedAt()).isBetween(before, Instant.now());
	}

	@Test
	void refundsOfAPaymentAreListedOldestFirst() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		Refund second = refundRepository.saveAndFlush(refund(new BigDecimal("2.00"), now.minusSeconds(10)));
		Refund first = refundRepository.saveAndFlush(refund(new BigDecimal("1.00"), now.minusSeconds(20)));

		assertThat(refundRepository.findByTransactionIdOrderByCreatedAtAscIdAsc(payment.getId()))
			.extracting(Refund::getId)
			.containsExactly(first.getId(), second.getId());
	}

	@Test
	void refundIsFoundByItsMerchantsIdempotencyKey() {
		String key = "refund-key-" + UUID.randomUUID();
		Refund refund = refund(new BigDecimal("3.00"), null);
		refund.setIdempotencyKey(key);
		refund.setRequestHash("d".repeat(64));
		UUID id = refundRepository.saveAndFlush(refund).getId();

		assertThat(refundRepository.findByMerchantIdAndIdempotencyKey(DEMO_MERCHANT_ID, key))
			.hasValueSatisfying((found) -> assertThat(found.getId()).isEqualTo(id));
		assertThat(refundRepository.findByMerchantIdAndIdempotencyKey(UUID.randomUUID(), key)).isEmpty();
	}

	private Refund refund(BigDecimal amount, Instant createdAt) {
		Refund refund = new Refund();
		refund.setTransaction(payment);
		refund.setMerchant(merchant);
		refund.setAmount(amount);
		refund.setStatus(RefundStatus.PENDING);
		refund.setCreatedAt(createdAt);
		return refund;
	}

}
