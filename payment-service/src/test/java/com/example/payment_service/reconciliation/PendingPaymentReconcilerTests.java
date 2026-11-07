package com.example.payment_service.reconciliation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.provider.PaymentProviderRegistry;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.ProviderStatusRequest;
import com.example.payment_service.provider.StatusCheckingProvider;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(OutputCaptureExtension.class)
class PendingPaymentReconcilerTests {

	private static final Duration INTERVAL = Duration.ofMinutes(1);

	private static final Duration MAX_BACKOFF = Duration.ofHours(1);

	private final TransactionRepository transactionRepository = mock();

	private final PaymentProviderRegistry providerRegistry = mock();

	private final PaymentService paymentService = mock();

	private final PlatformTransactionManager transactionManager = mock();

	private final StatusCheckingProvider provider = mock();

	private final PendingPaymentReconciler reconciler = new PendingPaymentReconciler(transactionRepository,
			providerRegistry, paymentService, new TransactionTemplate(transactionManager),
			new ReconciliationProperties(true, INTERVAL, Duration.ofMinutes(2), 50, MAX_BACKOFF, Duration.ofHours(24)));

	@ParameterizedTest
	@CsvSource({ "0, 1", "1, 2", "2, 4", "3, 8", "4, 16", "5, 32", "6, 60", "7, 60", "40, 60", "2147483647, 60",
			"-1, 1" })
	void backoffDoublesFromTheIntervalUpToTheMaximum(int attempts, long minutes) {
		assertThat(PendingPaymentReconciler.backoff(attempts, INTERVAL, MAX_BACKOFF))
			.isEqualTo(Duration.ofMinutes(minutes));
	}

	@Test
	void withoutStatusCheckingProvidersNothingIsClaimed() {
		given(providerRegistry.statusCheckingProviders()).willReturn(Map.of());

		assertThat(reconciler.reconcileDuePayments()).isZero();

		verifyNoInteractions(transactionRepository, transactionManager, paymentService);
	}

	@Test
	void claimsWithBackoffCommitsThenCallsTheProviderThenSettles() {
		Transaction payment = pending(2, "ref-7", Instant.now());
		given(providerRegistry.statusCheckingProviders()).willReturn(Map.of("stub", provider));
		given(transactionRepository.findDueForStatusCheck(any(), eq(Set.of("stub")), eq(50)))
			.willReturn(List.of(payment));
		given(provider.checkStatus(any())).willReturn(ProviderResult.approved("ref-7"));
		given(paymentService.settleFromStatusCheck(payment.getId(), ProviderResult.approved("ref-7")))
			.willReturn(TransactionStatus.SUCCEEDED);
		Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

		assertThat(reconciler.reconcileDuePayments()).isEqualTo(1);

		assertThat(payment.getCheckAttempts()).isEqualTo(3);
		assertThat(payment.getNextCheckAt()).isBetween(before.plus(Duration.ofMinutes(4)),
				Instant.now().plus(Duration.ofMinutes(4)));
		InOrder order = inOrder(transactionManager, transactionRepository, provider, paymentService);
		order.verify(transactionManager).getTransaction(any());
		order.verify(transactionRepository).findDueForStatusCheck(any(), any(), eq(50));
		order.verify(transactionManager).commit(any());
		order.verify(provider).checkStatus(new ProviderStatusRequest(payment.getId(), "ref-7"));
		order.verify(paymentService).settleFromStatusCheck(payment.getId(), ProviderResult.approved("ref-7"));
	}

	@Test
	void claimAsksOnlyForProvidersThatCheckStatus() {
		given(providerRegistry.statusCheckingProviders()).willReturn(Map.of("http", provider, "stub", provider));
		given(transactionRepository.findDueForStatusCheck(any(), any(), eq(50))).willReturn(List.of());

		reconciler.reconcileDuePayments();

		ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
		verify(transactionRepository).findDueForStatusCheck(now.capture(), eq(Set.of("http", "stub")), eq(50));
		assertThat(now.getValue()).isBetween(Instant.now().minusSeconds(5), Instant.now());
	}

	@Test
	void aFailingCheckDoesNotStopTheOthersAndIsNotSettled(CapturedOutput output) {
		Transaction failing = pending(0, null, Instant.now());
		Transaction working = pending(0, "ref-2", Instant.now());
		given(providerRegistry.statusCheckingProviders()).willReturn(Map.of("stub", provider));
		given(transactionRepository.findDueForStatusCheck(any(), any(), eq(50))).willReturn(List.of(failing, working));
		given(provider.checkStatus(new ProviderStatusRequest(failing.getId(), null)))
			.willThrow(new IllegalStateException("provider timed out"));
		given(provider.checkStatus(new ProviderStatusRequest(working.getId(), "ref-2")))
			.willReturn(ProviderResult.pending("ref-2"));
		given(paymentService.settleFromStatusCheck(working.getId(), ProviderResult.pending("ref-2")))
			.willReturn(TransactionStatus.PENDING);

		assertThat(reconciler.reconcileDuePayments()).isEqualTo(2);

		verify(paymentService, never()).settleFromStatusCheck(eq(failing.getId()), any());
		assertThat(failing.getCheckAttempts()).isEqualTo(1);
		assertThat(output).contains("Status check 1 for payment " + failing.getId()
				+ " with provider stub failed; next check after backoff: java.lang.IllegalStateException: "
				+ "provider timed out")
			.contains("Checked 2 pending payments with their providers: 0 settled, 1 still pending, 1 failed")
			.doesNotContain("ERROR");
	}

	@Test
	void anOldPaymentThatStaysPendingIsLoggedAtErrorOnEveryAttempt(CapturedOutput output) {
		Transaction oldPending = pending(9, null, Instant.now().minus(Duration.ofHours(25)));
		Transaction oldFailing = pending(9, null, Instant.now().minus(Duration.ofHours(30)));
		Transaction oldSettled = pending(9, null, Instant.now().minus(Duration.ofHours(26)));
		Transaction youngPending = pending(0, null, Instant.now().minus(Duration.ofHours(23)));
		given(providerRegistry.statusCheckingProviders()).willReturn(Map.of("stub", provider));
		given(transactionRepository.findDueForStatusCheck(any(), any(), eq(50)))
			.willReturn(List.of(oldPending, oldFailing, oldSettled, youngPending));
		given(provider.checkStatus(new ProviderStatusRequest(oldFailing.getId(), null)))
			.willThrow(new IllegalStateException("down"));
		given(provider.checkStatus(new ProviderStatusRequest(oldPending.getId(), null)))
			.willReturn(ProviderResult.pending("ref-old"));
		given(provider.checkStatus(new ProviderStatusRequest(youngPending.getId(), null)))
			.willReturn(ProviderResult.pending("ref-young"));
		given(provider.checkStatus(new ProviderStatusRequest(oldSettled.getId(), null)))
			.willReturn(ProviderResult.approved());
		given(paymentService.settleFromStatusCheck(eq(oldPending.getId()), any())).willReturn(TransactionStatus.PENDING);
		given(paymentService.settleFromStatusCheck(eq(youngPending.getId()), any()))
			.willReturn(TransactionStatus.PENDING);
		given(paymentService.settleFromStatusCheck(eq(oldSettled.getId()), any()))
			.willReturn(TransactionStatus.SUCCEEDED);

		reconciler.reconcileDuePayments();

		assertThat(output).containsOnlyOnce("Payment " + oldPending.getId() + " with provider stub is still PENDING PT25H")
			.containsOnlyOnce("Payment " + oldFailing.getId() + " with provider stub is still PENDING PT30H")
			.contains("(check 10); check it with the provider")
			.doesNotContain("Payment " + oldSettled.getId() + " with provider stub is still PENDING")
			.doesNotContain("Payment " + youngPending.getId() + " with provider stub is still PENDING");
	}

	@Test
	void aScheduledRunNeverThrows(CapturedOutput output) {
		given(providerRegistry.statusCheckingProviders()).willThrow(new IllegalStateException("database down"));

		assertThatCode(reconciler::runScheduled).doesNotThrowAnyException();

		assertThat(output).contains("Pending payment reconciliation failed; it runs again in PT1M")
			.contains("database down");
	}

	private static Transaction pending(int attempts, String reference, Instant createdAt) {
		Transaction transaction = new Transaction();
		transaction.setId(UUID.randomUUID());
		transaction.setAmount(new BigDecimal("10.00"));
		transaction.setCurrency("EUR");
		transaction.setPaymentMethod(PaymentMethod.CARD);
		transaction.setStatus(TransactionStatus.PENDING);
		transaction.setProvider("stub");
		transaction.setProviderReference(reference);
		transaction.setCreatedAt(createdAt);
		transaction.setNextCheckAt(Instant.now().minusSeconds(1));
		transaction.setCheckAttempts(attempts);
		return transaction;
	}

}
