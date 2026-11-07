package com.example.payment_service.authorization;

import java.math.BigDecimal;
import java.sql.Timestamp;
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
import com.example.payment_service.dto.PageResponse;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.CaptureMethod;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.PendingAction;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthorizationMappingTests {

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
	void authorizationFieldsAreStoredAndLoaded() {
		Instant expires = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
		Transaction payment = payment(TransactionStatus.SUCCEEDED);
		payment.setCaptureMethod(CaptureMethod.MANUAL);
		payment.setAuthorizedAmount(new BigDecimal("49.90"));
		payment.setCapturedAmount(new BigDecimal("30.00"));
		payment.setAuthorizationExpiresAt(expires);
		UUID id = transactionRepository.saveAndFlush(payment).getId();

		Map<String, Object> row = jdbcTemplate.queryForMap("""
				SELECT status, capture_method, authorized_amount, captured_amount, authorization_expires_at
				FROM transactions WHERE id = ?
				""", id);
		assertThat(row).containsEntry("status", "SUCCEEDED")
			.containsEntry("capture_method", "MANUAL")
			.containsEntry("authorized_amount", new BigDecimal("49.90"))
			.containsEntry("captured_amount", new BigDecimal("30.00"));
		assertThat(((Timestamp) row.get("authorization_expires_at")).toInstant()).isEqualTo(expires);
		Transaction loaded = transactionRepository.findById(id).orElseThrow();
		assertThat(loaded.getCaptureMethod()).isEqualTo(CaptureMethod.MANUAL);
		assertThat(loaded.getCapturedAmount()).isEqualTo(new BigDecimal("30.00"));
		assertThat(loaded.getAuthorizationExpiresAt()).isEqualTo(expires);
	}

	@Test
	void aPendingActionAndItsKeyAreStoredAndLoaded() {
		Transaction payment = payment(TransactionStatus.AUTHORIZED);
		payment.setCaptureMethod(CaptureMethod.MANUAL);
		payment.setAuthorizedAmount(new BigDecimal("49.90"));
		payment.setAuthorizationExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
		payment.setPendingAction(PendingAction.CAPTURE);
		payment.setPendingCaptureAmount(new BigDecimal("30.00"));
		payment.setActionIdempotencyKey("ship-1");
		payment.setActionRequestHash("b".repeat(64));
		UUID id = transactionRepository.saveAndFlush(payment).getId();

		assertThat(jdbcTemplate.queryForMap("""
				SELECT pending_action, pending_capture_amount, action_idempotency_key, action_request_hash
				FROM transactions WHERE id = ?
				""", id)).containsEntry("pending_action", "CAPTURE")
			.containsEntry("pending_capture_amount", new BigDecimal("30.00"))
			.containsEntry("action_idempotency_key", "ship-1")
			.containsEntry("action_request_hash", "b".repeat(64));
		Transaction loaded = transactionRepository.findById(id).orElseThrow();
		assertThat(loaded.getPendingAction()).isEqualTo(PendingAction.CAPTURE);
		assertThat(loaded.getPendingCaptureAmount()).isEqualTo(new BigDecimal("30.00"));
		assertThat(loaded.getActionIdempotencyKey()).isEqualTo("ship-1");
	}

	@Test
	void aNewTransactionIsAutomaticUnlessSaidOtherwise() {
		UUID id = transactionRepository.saveAndFlush(payment(TransactionStatus.SUCCEEDED)).getId();

		assertThat(jdbcTemplate.queryForObject("SELECT capture_method FROM transactions WHERE id = ?", String.class, id))
			.isEqualTo("AUTOMATIC");
	}

	@Test
	void theListFilterFindsAuthorizedAndVoidedPayments() {
		Transaction authorized = payment(TransactionStatus.AUTHORIZED);
		authorized.setCaptureMethod(CaptureMethod.MANUAL);
		authorized.setAuthorizedAmount(new BigDecimal("49.90"));
		authorized.setAuthorizationExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
		Transaction voided = payment(TransactionStatus.VOIDED);
		voided.setCaptureMethod(CaptureMethod.MANUAL);
		voided.setAuthorizedAmount(new BigDecimal("49.90"));
		UUID authorizedId = transactionRepository.saveAndFlush(authorized).getId();
		UUID voidedId = transactionRepository.saveAndFlush(voided).getId();
		Merchant merchant = merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow();

		PageResponse<PaymentResponse> authorizedPage = paymentService.list(merchant, TransactionStatus.AUTHORIZED, 0, 100);
		PageResponse<PaymentResponse> voidedPage = paymentService.list(merchant, TransactionStatus.VOIDED, 0, 100);

		assertThat(authorizedPage.content()).extracting(PaymentResponse::id).contains(authorizedId).doesNotContain(voidedId);
		assertThat(authorizedPage.content()).extracting(PaymentResponse::status).containsOnly(TransactionStatus.AUTHORIZED);
		assertThat(voidedPage.content()).extracting(PaymentResponse::id).contains(voidedId).doesNotContain(authorizedId);
	}

	private Transaction payment(TransactionStatus status) {
		Transaction transaction = new Transaction();
		transaction.setMerchant(merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow());
		transaction.setAmount(new BigDecimal("49.90"));
		transaction.setCurrency("EUR");
		transaction.setPaymentMethod(PaymentMethod.CARD);
		transaction.setStatus(status);
		transaction.setProvider("simulated");
		return transaction;
	}

}
