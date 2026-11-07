package com.example.payment_service.authorization;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.CaptureMethod;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.provider.AuthorizationResult;
import com.example.payment_service.provider.SimulatedPaymentProvider;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.RefundRepository;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.CaptureService;
import com.example.payment_service.service.PaymentOutcome;
import com.example.payment_service.service.PaymentService;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.entity.PendingAction;
import com.example.payment_service.error.CaptureDeclinedException;
import com.example.payment_service.error.CaptureExceedsAuthorizedException;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.ManualCaptureNotSupportedException;
import com.example.payment_service.error.PaymentNotCapturableException;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.error.PaymentNotVoidableException;
import com.example.payment_service.error.RefundExceedsRemainingException;
import com.example.payment_service.error.VoidDeclinedException;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.CaptureRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.VoidRequest;
import com.example.payment_service.service.RefundService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class CaptureAndVoidIntegrationTests {

	private static final Instant EXPIRES = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private CaptureService captureService;

	@Autowired
	private RefundService refundService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private RefundRepository refundRepository;

	@MockitoSpyBean
	private SimulatedPaymentProvider paymentProvider;

	@Autowired
	private ApplicationEvents events;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Merchant otherMerchant;

	@AfterEach
	void cleanUp() {
		refundRepository.deleteAll();
		transactionRepository.deleteAll();
		if (otherMerchant != null) {
			merchantRepository.delete(otherMerchant);
		}
	}

	@Test
	void aFullCaptureIsTheDefault() {
		UUID id = authorized("49.90");
		willReturn(ProviderResult.approved("sim_c_1")).given(paymentProvider).capture(any());

		PaymentOutcome outcome = captureService.capture(demoMerchant(), id, null, null);

		PaymentResponse payment = outcome.payment();
		assertThat(outcome.replayed()).isFalse();
		assertThat(payment.status()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(payment.capturedAmount()).isEqualByComparingTo("49.90");
		assertThat(payment.pendingAction()).isNull();
		assertThat(payment.processedAt()).isNotNull();
		then(paymentProvider).should()
			.capture(new CaptureRequest(id, referenceOf(id), "EUR", new BigDecimal("49.90")));
		assertThat(events.stream(PaymentProcessedEvent.class)).singleElement().satisfies((event) -> {
			assertThat(event.status()).isEqualTo(TransactionStatus.SUCCEEDED);
			assertThat(event.amount()).isEqualByComparingTo("49.90");
		});
		assertThat(paymentService.get(demoMerchant(), id)).isEqualTo(payment);
	}

	@Test
	void aPartialCaptureKeepsTheAmountAndChargesLess() {
		UUID id = authorized("49.90");
		willReturn(ProviderResult.approved("sim_c_1")).given(paymentProvider).capture(any());

		PaymentResponse payment = captureService.capture(demoMerchant(), id, new BigDecimal("30"), null).payment();

		assertThat(payment.amount()).isEqualByComparingTo("49.90");
		assertThat(payment.capturedAmount()).isEqualByComparingTo("30.00");
		assertThat(row(id)).containsEntry("status", "SUCCEEDED")
			.containsEntry("captured_amount", new BigDecimal("30.00"))
			.containsEntry("pending_action", null)
			.containsEntry("pending_capture_amount", null);
		assertThat(events.stream(PaymentProcessedEvent.class)).singleElement()
			.satisfies((event) -> assertThat(event.amount()).isEqualByComparingTo("30.00"));
	}

	@Test
	void aCapturedPaymentIsNeitherCapturedNorVoidedAgain() {
		UUID id = authorized("49.90");
		willReturn(ProviderResult.approved("sim_c_1")).given(paymentProvider).capture(any());
		captureService.capture(demoMerchant(), id, null, null);

		assertThatExceptionOfType(PaymentNotCapturableException.class)
			.isThrownBy(() -> captureService.capture(demoMerchant(), id, null, null))
			.withMessage("Payment " + id + " is SUCCEEDED; only AUTHORIZED payments can be captured");
		assertThatExceptionOfType(PaymentNotVoidableException.class)
			.isThrownBy(() -> captureService.voidAuthorization(demoMerchant(), id, null))
			.withMessage("Payment " + id + " is SUCCEEDED; only AUTHORIZED payments can be voided");
		then(paymentProvider).should(times(1)).capture(any());
		then(paymentProvider).should(never()).voidAuthorization(any());
		assertThat(events.stream(PaymentProcessedEvent.class)).hasSize(1);
	}

	@Test
	void aVoidedPaymentIsReleasedOnceAndCannotBeCaptured() {
		UUID id = authorized("49.90");
		willReturn(ProviderResult.approved("sim_v_1")).given(paymentProvider).voidAuthorization(any());

		PaymentResponse payment = captureService.voidAuthorization(demoMerchant(), id, null).payment();

		assertThat(payment.status()).isEqualTo(TransactionStatus.VOIDED);
		assertThat(payment.capturedAmount()).isNull();
		assertThat(payment.processedAt()).isNotNull();
		then(paymentProvider).should().voidAuthorization(new VoidRequest(id, referenceOf(id)));
		assertThatExceptionOfType(PaymentNotCapturableException.class)
			.isThrownBy(() -> captureService.capture(demoMerchant(), id, null, null))
			.withMessage("Payment " + id + " is VOIDED; only AUTHORIZED payments can be captured");
		assertThatExceptionOfType(PaymentNotVoidableException.class)
			.isThrownBy(() -> captureService.voidAuthorization(demoMerchant(), id, null));
		then(paymentProvider).should(never()).capture(any());
		assertThat(events.stream(PaymentProcessedEvent.class)).singleElement().satisfies((event) -> {
			assertThat(event.status()).isEqualTo(TransactionStatus.VOIDED);
			assertThat(event.amount()).isEqualByComparingTo("49.90");
		});
	}

	@Test
	void theCaptureCannotExceedTheAuthorizedAmount() {
		UUID id = authorized("49.90");

		assertThatExceptionOfType(CaptureExceedsAuthorizedException.class)
			.isThrownBy(() -> captureService.capture(demoMerchant(), id, new BigDecimal("49.91"), null))
			.withMessage("Capture 49.91 exceeds the authorized amount 49.90");

		assertThat(row(id)).containsEntry("status", "AUTHORIZED").containsEntry("pending_action", null);
		then(paymentProvider).should(never()).capture(any());
	}

	@Test
	void anExpiredAuthorizationCanOnlyBeVoided() {
		UUID id = authorized("49.90");
		jdbcTemplate.update("UPDATE transactions SET authorization_expires_at = now() - interval '1 minute' WHERE id = ?",
				id);
		willReturn(ProviderResult.approved("sim_v_1")).given(paymentProvider).voidAuthorization(any());

		assertThatExceptionOfType(PaymentNotCapturableException.class)
			.isThrownBy(() -> captureService.capture(demoMerchant(), id, null, null))
			.withMessageContaining("The authorization of payment " + id + " expired at ")
			.withMessageEndingWith("; it can only be voided");
		assertThat(captureService.voidAuthorization(demoMerchant(), id, null).payment().status())
			.isEqualTo(TransactionStatus.VOIDED);
		then(paymentProvider).should(never()).capture(any());
	}

	@Test
	void aProviderFailureLeavesTheCapturePending() {
		UUID id = authorized("49.90");
		willThrow(new IllegalStateException("Provider unreachable")).given(paymentProvider).capture(any());

		PaymentResponse payment = captureService.capture(demoMerchant(), id, new BigDecimal("30.00"), null).payment();

		assertThat(payment.status()).isEqualTo(TransactionStatus.AUTHORIZED);
		assertThat(payment.pendingAction()).isEqualTo(PendingAction.CAPTURE);
		assertThat(payment.capturedAmount()).isNull();
		assertThat(row(id)).containsEntry("pending_action", "CAPTURE")
			.containsEntry("pending_capture_amount", new BigDecimal("30.00"))
			.containsEntry("captured_amount", null);
		assertThatExceptionOfType(PaymentNotVoidableException.class)
			.isThrownBy(() -> captureService.voidAuthorization(demoMerchant(), id, null))
			.withMessage("A capture of payment " + id + " is already in progress");
		assertThatExceptionOfType(PaymentNotCapturableException.class)
			.isThrownBy(() -> captureService.capture(demoMerchant(), id, null, null))
			.withMessage("A capture of payment " + id + " is already in progress");
		assertThat(paymentService.get(demoMerchant(), id).pendingAction()).isEqualTo(PendingAction.CAPTURE);
		then(paymentProvider).should(times(1)).capture(any());
		then(paymentProvider).should(never()).voidAuthorization(any());
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void aPendingProviderAnswerAlsoLeavesTheVoidPending() {
		UUID id = authorized("49.90");
		willReturn(ProviderResult.pending("sim_v_1")).given(paymentProvider).voidAuthorization(any());

		PaymentResponse payment = captureService.voidAuthorization(demoMerchant(), id, null).payment();

		assertThat(payment.status()).isEqualTo(TransactionStatus.AUTHORIZED);
		assertThat(payment.pendingAction()).isEqualTo(PendingAction.VOID);
		assertThat(row(id)).containsEntry("pending_action", "VOID");
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void aDeclinedCaptureLeavesThePaymentAuthorizedAndTheKeyFree() {
		UUID id = authorized("49.90");
		willReturn(ProviderResult.declined("Authorization cancelled by the bank", "sim_c_1"),
				ProviderResult.approved("sim_c_2"))
			.given(paymentProvider)
			.capture(any());

		assertThatExceptionOfType(CaptureDeclinedException.class)
			.isThrownBy(() -> captureService.capture(demoMerchant(), id, null, "ship-1"))
			.withMessage("The payment provider declined the capture: Authorization cancelled by the bank. "
					+ "The payment stays AUTHORIZED");

		assertThat(row(id)).containsEntry("status", "AUTHORIZED")
			.containsEntry("pending_action", null)
			.containsEntry("pending_capture_amount", null)
			.containsEntry("action_idempotency_key", null)
			.containsEntry("action_request_hash", null);
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
		PaymentOutcome retry = captureService.capture(demoMerchant(), id, null, "ship-1");
		assertThat(retry.replayed()).isFalse();
		assertThat(retry.payment().status()).isEqualTo(TransactionStatus.SUCCEEDED);
	}

	@Test
	void aDeclinedVoidLeavesThePaymentAuthorized() {
		UUID id = authorized("49.90");
		willReturn(ProviderResult.declined("Already captured", "sim_v_1")).given(paymentProvider)
			.voidAuthorization(any());
		willReturn(ProviderResult.approved("sim_c_1")).given(paymentProvider).capture(any());

		assertThatExceptionOfType(VoidDeclinedException.class)
			.isThrownBy(() -> captureService.voidAuthorization(demoMerchant(), id, null));

		assertThat(row(id)).containsEntry("status", "AUTHORIZED").containsEntry("pending_action", null);
		assertThat(captureService.capture(demoMerchant(), id, null, null).payment().status())
			.isEqualTo(TransactionStatus.SUCCEEDED);
	}

	@Test
	void aRetriedCaptureIsReplayedWithoutCallingTheProviderAgain() {
		UUID id = authorized("49.90");
		willReturn(ProviderResult.approved("sim_c_1")).given(paymentProvider).capture(any());

		PaymentOutcome first = captureService.capture(demoMerchant(), id, new BigDecimal("30.00"), "ship-1");
		PaymentOutcome retry = captureService.capture(demoMerchant(), id, new BigDecimal("30"), "ship-1");

		assertThat(retry.replayed()).isTrue();
		assertThat(retry.payment()).isEqualTo(first.payment());
		assertThatExceptionOfType(IdempotencyKeyReusedException.class)
			.isThrownBy(() -> captureService.capture(demoMerchant(), id, new BigDecimal("31.00"), "ship-1"));
		assertThatExceptionOfType(IdempotencyKeyReusedException.class)
			.isThrownBy(() -> captureService.voidAuthorization(demoMerchant(), id, "ship-1"));
		then(paymentProvider).should(times(1)).capture(any());
		assertThat(events.stream(PaymentProcessedEvent.class)).hasSize(1);
	}

	@Test
	void aRetryWhileTheCaptureIsUnknownIsReplayedAsPending() {
		UUID id = authorized("49.90");
		willThrow(new IllegalStateException("Read timed out")).given(paymentProvider).capture(any());
		captureService.capture(demoMerchant(), id, null, "ship-1");

		PaymentOutcome retry = captureService.capture(demoMerchant(), id, null, "ship-1");

		assertThat(retry.replayed()).isTrue();
		assertThat(retry.payment().pendingAction()).isEqualTo(PendingAction.CAPTURE);
		then(paymentProvider).should(times(1)).capture(any());
	}

	@Test
	void anotherMerchantCanNeitherCaptureNorVoid() {
		UUID id = authorized("49.90");
		otherMerchant = createMerchant();

		assertThatExceptionOfType(PaymentNotFoundException.class)
			.isThrownBy(() -> captureService.capture(otherMerchant, id, null, null));
		assertThatExceptionOfType(PaymentNotFoundException.class)
			.isThrownBy(() -> captureService.voidAuthorization(otherMerchant, id, null));

		assertThat(row(id)).containsEntry("status", "AUTHORIZED").containsEntry("pending_action", null);
		then(paymentProvider).should(never()).capture(any());
		then(paymentProvider).should(never()).voidAuthorization(any());
	}

	@Test
	void anAutomaticPaymentCanNeitherBeCapturedNorVoided() {
		willReturn(ProviderResult.approved("sim_" + UUID.randomUUID())).given(paymentProvider).process(any());
		UUID id = paymentService
			.process(demoMerchant(), new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD))
			.id();

		assertThatExceptionOfType(PaymentNotCapturableException.class)
			.isThrownBy(() -> captureService.capture(demoMerchant(), id, null, null));
		assertThatExceptionOfType(PaymentNotVoidableException.class)
			.isThrownBy(() -> captureService.voidAuthorization(demoMerchant(), id, null));
	}

	@Test
	void aProviderThatNoLongerSupportsManualCaptureIsRefused() {
		UUID id = authorized("49.90");
		willReturn(false).given(paymentProvider).supportsManualCapture();

		assertThatExceptionOfType(ManualCaptureNotSupportedException.class)
			.isThrownBy(() -> captureService.capture(demoMerchant(), id, null, null));

		assertThat(row(id)).containsEntry("status", "AUTHORIZED").containsEntry("pending_action", null);
	}

	@Test
	void aLateAnswerIsIgnoredWhenThePaymentWasSettledMeanwhile() {
		UUID id = authorized("49.90");
		willAnswer((invocation) -> {
			jdbcTemplate.update("""
					UPDATE transactions SET status = 'VOIDED', pending_action = NULL, pending_capture_amount = NULL,
					       processed_at = now() WHERE id = ?
					""", id);
			return ProviderResult.approved("sim_c_1");
		}).given(paymentProvider).capture(any());

		PaymentResponse payment = captureService.capture(demoMerchant(), id, null, null).payment();

		assertThat(payment.status()).isEqualTo(TransactionStatus.VOIDED);
		assertThat(payment.capturedAmount()).isNull();
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void aCapturedPaymentIsRefundableUpToTheCapturedAmount() {
		UUID id = authorized("49.90");
		willReturn(ProviderResult.approved("sim_c_1")).given(paymentProvider).capture(any());
		willReturn(ProviderResult.approved("sim_r_1")).given(paymentProvider).refund(any());
		captureService.capture(demoMerchant(), id, new BigDecimal("30.00"), null);

		assertThatExceptionOfType(RefundExceedsRemainingException.class)
			.isThrownBy(() -> refundService.create(demoMerchant(), id, new BigDecimal("30.01"), null, null))
			.withMessage("Refund 30.01 exceeds the remaining refundable amount 30.00");
		assertThat(refundService.create(demoMerchant(), id, null, null, null).refund().amount())
			.isEqualByComparingTo("30.00");
		assertThat(paymentService.get(demoMerchant(), id).refundedAmount()).isEqualByComparingTo("30.00");
	}

	private UUID authorized(String amount) {
		willReturn(AuthorizationResult.approved("sim_a_" + UUID.randomUUID(), EXPIRES)).given(paymentProvider)
			.authorize(any());
		PaymentResponse payment = paymentService.process(demoMerchant(),
				new CreatePaymentRequest(new BigDecimal(amount), "EUR", PaymentMethod.CARD, CaptureMethod.MANUAL));
		assertThat(payment.status()).isEqualTo(TransactionStatus.AUTHORIZED);
		return payment.id();
	}

	private String referenceOf(UUID id) {
		return transactionRepository.findById(id).orElseThrow().getProviderReference();
	}

	private Map<String, Object> row(UUID id) {
		return jdbcTemplate.queryForMap("""
				SELECT status, captured_amount, pending_action, pending_capture_amount, action_idempotency_key,
				       action_request_hash
				FROM transactions WHERE id = ?
				""", id);
	}

	private Merchant demoMerchant() {
		return merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("demo-key-123")).orElseThrow();
	}

	private Merchant createMerchant() {
		Merchant merchant = new Merchant();
		merchant.setName("Other Shop");
		merchant.setApiKeyHash(ApiKeyHasher.hash(UUID.randomUUID().toString()));
		merchant.setStatus(MerchantStatus.ACTIVE);
		return merchantRepository.save(merchant);
	}

}
