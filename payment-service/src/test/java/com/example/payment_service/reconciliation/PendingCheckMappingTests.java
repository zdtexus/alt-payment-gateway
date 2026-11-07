package com.example.payment_service.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PendingCheckMappingTests {

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void nextCheckAndAttemptsAreStoredAndLoaded() {
		Instant due = Instant.now().plusSeconds(90).truncatedTo(ChronoUnit.MICROS);
		Transaction transaction = new Transaction();
		transaction.setMerchant(merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow());
		transaction.setAmount(new BigDecimal("10.00"));
		transaction.setCurrency("EUR");
		transaction.setPaymentMethod(PaymentMethod.CARD);
		transaction.setStatus(TransactionStatus.PENDING);
		transaction.setProvider("http");
		transaction.setNextCheckAt(due);
		transaction.setCheckAttempts(3);
		UUID id = transactionRepository.saveAndFlush(transaction).getId();

		Map<String, Object> row = jdbcTemplate
			.queryForMap("SELECT next_check_at, check_attempts FROM transactions WHERE id = ?", id);
		assertThat(((java.sql.Timestamp) row.get("next_check_at")).toInstant()).isEqualTo(due);
		assertThat(row).containsEntry("check_attempts", 3);
		Transaction loaded = transactionRepository.findById(id).orElseThrow();
		assertThat(loaded.getNextCheckAt()).isEqualTo(due);
		assertThat(loaded.getCheckAttempts()).isEqualTo(3);
	}

	@Test
	void finalPaymentIsStoredWithoutANextCheck() {
		Merchant merchant = merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow();

		PaymentResponse payment = paymentService.process(merchant,
				new CreatePaymentRequest(new BigDecimal("12.00"), "EUR", PaymentMethod.CARD));

		assertThat(payment.status()).isIn(TransactionStatus.SUCCEEDED, TransactionStatus.DECLINED);
		Map<String, Object> row = jdbcTemplate
			.queryForMap("SELECT next_check_at, check_attempts FROM transactions WHERE id = ?", payment.id());
		assertThat(row).containsEntry("next_check_at", null).containsEntry("check_attempts", 0);
	}

}
