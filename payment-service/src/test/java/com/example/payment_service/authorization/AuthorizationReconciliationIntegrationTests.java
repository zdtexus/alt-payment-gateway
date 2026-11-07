package com.example.payment_service.authorization;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.authorization.StubAuthorizationProviderConfig.StubAuthorizationProvider;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.CaptureMethod;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.PendingAction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.AuthorizationResult;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.SimulatedPaymentProvider;
import com.example.payment_service.reconciliation.PendingPaymentReconciler;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.RefundRepository;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.CaptureService;
import com.example.payment_service.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.times;

@SpringBootTest(properties = "payment.provider.routes.KVIK=" + StubAuthorizationProvider.NAME)
@Import({ TestcontainersConfiguration.class, StubAuthorizationProviderConfig.class })
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class AuthorizationReconciliationIntegrationTests {

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private CaptureService captureService;

	@Autowired
	private PendingPaymentReconciler reconciler;

	@Autowired
	private StubAuthorizationProvider stub;

	@MockitoSpyBean
	private SimulatedPaymentProvider simulated;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private RefundRepository refundRepository;

	@Autowired
	private ApplicationEvents events;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	@AfterEach
	void cleanUp() {
		stub.reset();
		refundRepository.deleteAll();
		transactionRepository.deleteAll();
	}

	@Test
	void aPendingAuthorizationIsAuthorizedByItsStatusCheck() {
		stub.authorize = (request) -> AuthorizationResult.pending("stub_a_" + request.paymentId());
		UUID id = create(PaymentMethod.KVIK).id();
		Instant expires = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
		stub.authorizationStatus = () -> AuthorizationResult.approved("stub_a_" + id, expires);
		makeDue(id);

		reconciler.reconcileDuePayments();
		reconciler.reconcileDuePayments();

		PaymentResponse payment = paymentService.get(demoMerchant(), id);
		assertThat(payment.status()).isEqualTo(TransactionStatus.AUTHORIZED);
		assertThat(payment.authorizationExpiresAt()).isEqualTo(expires);
		assertThat(row(id)).containsEntry("next_check_at", null)
			.containsEntry("authorized_amount", new BigDecimal("49.90"))
			.containsEntry("check_attempts", 0);
		assertThat(stub.calls("authorizationStatus")).isEqualTo(1);
		assertThat(stub.calls("checkStatus")).isZero();
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void aPendingAuthorizationDeclinedOnItsCheckGetsOneReceipt() {
		stub.authorize = (request) -> AuthorizationResult.pending("stub_a_" + request.paymentId());
		UUID id = create(PaymentMethod.KVIK).id();
		stub.authorizationStatus = () -> AuthorizationResult.declined("Insufficient funds", "stub_a_" + id);
		makeDue(id);

		reconciler.reconcileDuePayments();

		assertThat(paymentService.get(demoMerchant(), id).status()).isEqualTo(TransactionStatus.DECLINED);
		assertThat(events.stream(PaymentProcessedEvent.class)).hasSize(1);
	}

	@Test
	void aCaptureWhoseAnswerIsUnknownIsScheduledAndCapturedOnceByItsStatusCheck() {
		UUID id = create(PaymentMethod.KVIK).id();
		stub.capture = () -> {
			throw new IllegalStateException("Read timed out");
		};
		captureService.capture(demoMerchant(), id, new BigDecimal("30.00"), null);
		Map<String, Object> scheduled = row(id);
		assertThat(((Timestamp) scheduled.get("next_check_at")).toInstant())
			.isEqualTo(((Timestamp) scheduled.get("action_requested_at")).toInstant().plus(2, ChronoUnit.MINUTES));
		stub.actionStatus = () -> ProviderResult.approved("stub_c_1");
		makeDue(id);

		reconciler.reconcileDuePayments();
		makeDue(id);
		reconciler.reconcileDuePayments();

		PaymentResponse payment = paymentService.get(demoMerchant(), id);
		assertThat(payment.status()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(payment.capturedAmount()).isEqualByComparingTo("30.00");
		assertThat(row(id)).containsEntry("pending_action", null)
			.containsEntry("next_check_at", null)
			.containsEntry("action_requested_at", null);
		assertThat(stub.calls("actionStatus:CAPTURE")).isEqualTo(1);
		assertThat(events.stream(PaymentProcessedEvent.class)).singleElement()
			.satisfies((event) -> assertThat(event.amount()).isEqualByComparingTo("30.00"));
	}

	@Test
	void aCaptureDeclinedOnItsCheckLeavesThePaymentAuthorized() {
		UUID id = create(PaymentMethod.KVIK).id();
		stub.capture = () -> ProviderResult.pending("stub_c_1");
		captureService.capture(demoMerchant(), id, null, "ship-1");
		stub.actionStatus = () -> ProviderResult.declined("Authorization cancelled", "stub_c_1");
		makeDue(id);

		reconciler.reconcileDuePayments();

		assertThat(row(id)).containsEntry("status", "AUTHORIZED")
			.containsEntry("pending_action", null)
			.containsEntry("action_idempotency_key", null)
			.containsEntry("next_check_at", null);
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void aPendingVoidIsVoidedByItsStatusCheck() {
		UUID id = create(PaymentMethod.KVIK).id();
		stub.voidAnswer = () -> ProviderResult.pending("stub_v_1");
		captureService.voidAuthorization(demoMerchant(), id, null);
		stub.actionStatus = () -> ProviderResult.approved("stub_v_1");
		makeDue(id);

		reconciler.reconcileDuePayments();

		assertThat(paymentService.get(demoMerchant(), id).status()).isEqualTo(TransactionStatus.VOIDED);
		assertThat(stub.calls("actionStatus:VOID")).isEqualTo(1);
		assertThat(events.stream(PaymentProcessedEvent.class)).singleElement()
			.satisfies((event) -> assertThat(event.status()).isEqualTo(TransactionStatus.VOIDED));
	}

	@Test
	void aCaptureStillPendingLongAfterItWasRequestedIsAlerted(CapturedOutput output) {
		UUID id = create(PaymentMethod.KVIK).id();
		stub.capture = () -> ProviderResult.pending("stub_c_1");
		captureService.capture(demoMerchant(), id, null, null);
		jdbcTemplate.update("UPDATE transactions SET action_requested_at = now() - interval '25 hours', "
				+ "next_check_at = now() - interval '1 minute' WHERE id = ?", id);

		reconciler.reconcileDuePayments();

		assertThat(output).contains("The capture of payment " + id + " with provider stub-auth is still pending");
		assertThat(row(id)).containsEntry("pending_action", "CAPTURE");
	}

	@Test
	void anExpiredAuthorizationIsVoidedOnceWithOneReceiptEvenWithoutStatusChecks() {
		willReturn(AuthorizationResult.approved("sim_a_" + UUID.randomUUID(), Instant.now().minusSeconds(60)))
			.given(simulated)
			.authorize(any());
		willReturn(ProviderResult.approved("sim_v_1")).given(simulated).voidAuthorization(any());
		UUID id = create(PaymentMethod.CARD).id();

		reconciler.reconcileDuePayments();
		reconciler.reconcileDuePayments();

		assertThat(paymentService.get(demoMerchant(), id).status()).isEqualTo(TransactionStatus.VOIDED);
		assertThat(row(id)).containsEntry("next_check_at", null).containsEntry("pending_action", null);
		then(simulated).should(times(1)).voidAuthorization(any());
		assertThat(events.stream(PaymentProcessedEvent.class)).singleElement()
			.satisfies((event) -> assertThat(event.status()).isEqualTo(TransactionStatus.VOIDED));
	}

	@Test
	void aDeclinedExpiryVoidIsRetriedAfterBackoff(CapturedOutput output) {
		stub.authorize = (request) -> AuthorizationResult.approved("stub_a_" + request.paymentId(),
				Instant.now().minusSeconds(60));
		UUID id = create(PaymentMethod.KVIK).id();
		stub.voidAnswer = () -> ProviderResult.declined("Try later", "stub_v_1");

		reconciler.reconcileDuePayments();
		reconciler.reconcileDuePayments();

		Map<String, Object> afterDecline = row(id);
		assertThat(afterDecline).containsEntry("status", "AUTHORIZED")
			.containsEntry("pending_action", null)
			.containsEntry("action_requested_at", null)
			.containsEntry("check_attempts", 1);
		assertThat(((Timestamp) afterDecline.get("next_check_at")).toInstant()).isAfter(Instant.now());
		assertThat(stub.calls("void")).isEqualTo(1);
		assertThat(output).contains("Provider stub-auth declined voiding the authorization of payment " + id);
		stub.voidAnswer = () -> ProviderResult.approved("stub_v_2");
		makeDue(id);

		reconciler.reconcileDuePayments();

		assertThat(paymentService.get(demoMerchant(), id).status()).isEqualTo(TransactionStatus.VOIDED);
		assertThat(stub.calls("void")).isEqualTo(2);
		assertThat(events.stream(PaymentProcessedEvent.class)).hasSize(1);
	}

	@Test
	void anExpiryVoidThatFailsReleasesTheClaimAndIsRetried(CapturedOutput output) {
		stub.authorize = (request) -> AuthorizationResult.approved("stub_a_" + request.paymentId(),
				Instant.now().minusSeconds(60));
		UUID id = create(PaymentMethod.KVIK).id();
		stub.voidAnswer = () -> {
			throw new IllegalStateException("Connection refused");
		};

		reconciler.reconcileDuePayments();

		Map<String, Object> afterFailure = row(id);
		assertThat(afterFailure).containsEntry("status", "AUTHORIZED").containsEntry("pending_action", null);
		assertThat(((Timestamp) afterFailure.get("next_check_at")).toInstant()).isAfter(Instant.now());
		assertThat(output).contains("Voiding the expired authorization of payment " + id + " with provider stub-auth failed");
	}

	@Test
	void authorizationsThatHaveNotExpiredAreLeftAlone() {
		UUID id = create(PaymentMethod.KVIK).id();

		reconciler.reconcileDuePayments();

		assertThat(row(id)).containsEntry("status", "AUTHORIZED").containsEntry("next_check_at", null);
		assertThat(stub.calls("void")).isZero();
	}

	@Test
	void anExpiredAuthorizationWithACaptureInProgressIsCheckedNotVoided() {
		UUID id = create(PaymentMethod.KVIK).id();
		stub.capture = () -> ProviderResult.pending("stub_c_1");
		captureService.capture(demoMerchant(), id, null, null);
		jdbcTemplate.update("UPDATE transactions SET authorization_expires_at = now() - interval '1 minute', "
				+ "next_check_at = now() - interval '1 minute' WHERE id = ?", id);

		reconciler.reconcileDuePayments();

		assertThat(stub.calls("void")).isZero();
		assertThat(stub.calls("actionStatus:CAPTURE")).isEqualTo(1);
		assertThat(row(id)).containsEntry("pending_action", "CAPTURE");
	}

	private PaymentResponse create(PaymentMethod method) {
		PaymentResponse payment = paymentService.process(demoMerchant(),
				new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", method, CaptureMethod.MANUAL));
		assertThat(payment.status()).isIn(TransactionStatus.AUTHORIZED, TransactionStatus.PENDING);
		return payment;
	}

	private void makeDue(UUID id) {
		jdbcTemplate.update("UPDATE transactions SET next_check_at = now() - interval '1 minute' WHERE id = ? AND next_check_at IS NOT NULL",
				id);
	}

	private Map<String, Object> row(UUID id) {
		return jdbcTemplate.queryForMap("""
				SELECT status, authorized_amount, pending_action, action_idempotency_key, action_requested_at,
				       next_check_at, check_attempts
				FROM transactions WHERE id = ?
				""", id);
	}

	private Merchant demoMerchant() {
		return merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("demo-key-123")).orElseThrow();
	}

}
