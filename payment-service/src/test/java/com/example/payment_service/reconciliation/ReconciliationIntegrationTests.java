package com.example.payment_service.reconciliation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.ProviderWebhook;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import({ TestcontainersConfiguration.class, StubStatusProviderConfig.class })
@ExtendWith(OutputCaptureExtension.class)
class ReconciliationIntegrationTests {

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final String STUB = StubStatusProviderConfig.StubStatusProvider.NAME;

	@Autowired
	private PendingPaymentReconciler reconciler;

	@Autowired
	private StubStatusProviderConfig.StubStatusProvider stub;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ApplicationContext context;

	@MockitoBean
	private EmailSender emailSender;

	@MockitoBean
	private SmsSender smsSender;

	private final ExecutorService executor = Executors.newFixedThreadPool(2);

	@BeforeEach
	void startEmpty() {
		jdbcTemplate.update("DELETE FROM refunds");
		jdbcTemplate.update("DELETE FROM transactions");
		stub.reset();
	}

	@AfterEach
	void stopThreads() {
		executor.shutdownNow();
	}

	@Test
	void duePendingPaymentIsSettledOnTheNextRunWithOneReceipt() {
		UUID id = pending(STUB, "ref-1", Instant.now().minusSeconds(600), Instant.now().minusSeconds(1));

		assertThat(reconciler.reconcileDuePayments()).isEqualTo(1);

		assertThat(row(id)).containsEntry("status", "SUCCEEDED")
			.containsEntry("next_check_at", null)
			.containsEntry("check_attempts", 1)
			.containsEntry("provider_reference", "ref-1");
		assertThat(row(id).get("processed_at")).isNotNull();
		verify(emailSender, timeout(5000)).send(eq("shop@demo.local"), eq("Payment " + id + " succeeded"), any());
		verify(smsSender, timeout(5000)).send("+380501112233", "Payment 49.90 EUR succeeded");
		assertThat(reconciler.reconcileDuePayments()).isZero();
		assertThat(stub.calls(id)).isEqualTo(1);
		verify(emailSender, after(1000).times(1)).send(any(), any(), any());
	}

	@Test
	void stillPendingAnswerBacksOffLongerEachTime() {
		stub.answer((request) -> ProviderResult.pending("ref-later"));
		UUID id = pending(STUB, null, Instant.now().minusSeconds(600), Instant.now().minusSeconds(1));

		Instant first = Instant.now();
		reconciler.reconcileDuePayments();
		assertThat(row(id)).containsEntry("status", "PENDING")
			.containsEntry("check_attempts", 1)
			.containsEntry("provider_reference", "ref-later");
		assertThat(nextCheck(id)).isBetween(first.plus(Duration.ofMinutes(1)).minusSeconds(1),
				Instant.now().plus(Duration.ofMinutes(1)));

		makeDue(id);
		Instant second = Instant.now();
		reconciler.reconcileDuePayments();
		assertThat(row(id)).containsEntry("check_attempts", 2);
		assertThat(nextCheck(id)).isBetween(second.plus(Duration.ofMinutes(2)).minusSeconds(1),
				Instant.now().plus(Duration.ofMinutes(2)));
		verify(emailSender, after(500).never()).send(any(), any(), any());
	}

	@Test
	void failedCheckOnlyWaitsForTheNextBackoff() {
		stub.answer((request) -> {
			throw new IllegalStateException("provider timed out");
		});
		UUID id = pending(STUB, "ref-1", Instant.now().minusSeconds(600), Instant.now().minusSeconds(1));

		assertThat(reconciler.reconcileDuePayments()).isEqualTo(1);

		assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("check_attempts", 1);
		assertThat(nextCheck(id)).isAfter(Instant.now().plusSeconds(50));
		verify(emailSender, after(500).never()).send(any(), any(), any());
	}

	@Test
	void oldPaymentThatStaysPendingIsLoggedAtError(CapturedOutput output) {
		stub.answer((request) -> ProviderResult.pending("ref-old"));
		UUID id = pending(STUB, "ref-old", Instant.now().minus(Duration.ofHours(25)), Instant.now().minusSeconds(1));

		reconciler.reconcileDuePayments();

		assertThat(output).contains("ERROR")
			.contains("Payment " + id + " with provider stub-status is still PENDING PT25H after it was created (check 1)");
	}

	@Test
	void webhookArrivingBetweenClaimAndAnswerWinsAndOnlyOneReceiptIsSent(CapturedOutput output) throws Exception {
		CountDownLatch inCheck = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		stub.beforeAnswer((request) -> {
			inCheck.countDown();
			await(release);
		});
		UUID id = pending(STUB, "ref-w", Instant.now().minusSeconds(600), Instant.now().minusSeconds(1));

		Future<Integer> run = executor.submit(reconciler::reconcileDuePayments);
		assertThat(inCheck.await(10, TimeUnit.SECONDS)).isTrue();
		paymentService.settleFromWebhook(STUB, new ProviderWebhook(null, ProviderResult.declined("Card expired", "ref-w")));
		release.countDown();

		assertThat(run.get(10, TimeUnit.SECONDS)).isEqualTo(1);
		assertThat(row(id)).containsEntry("status", "DECLINED").containsEntry("decline_reason", "Card expired");
		verify(emailSender, timeout(5000)).send(any(), eq("Payment " + id + " declined"), contains("Card expired"));
		verify(emailSender, after(1500).times(1)).send(any(), any(), any());
		assertThat(output).contains("Payment " + id + " was already DECLINED when its provider answered");
	}

	@Test
	void paymentsOfProvidersWithoutStatusChecksAreNeverClaimed() {
		Instant due = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		UUID simulated = pending("simulated", "ref-s", Instant.now().minusSeconds(600), due);

		assertThat(reconciler.reconcileDuePayments()).isZero();

		assertThat(row(simulated)).containsEntry("status", "PENDING").containsEntry("check_attempts", 0);
		assertThat(nextCheck(simulated)).isEqualTo(due);
		assertThat(stub.allCalls()).isEmpty();
	}

	@Test
	void paymentsNotYetDueAreLeftAlone() {
		UUID later = pending(STUB, "ref-l", Instant.now(), Instant.now().plusSeconds(600));

		assertThat(reconciler.reconcileDuePayments()).isZero();

		assertThat(row(later)).containsEntry("check_attempts", 0);
		assertThat(stub.calls(later)).isZero();
	}

	@Test
	void concurrentRunsNeverCheckOnePaymentTwice() throws Exception {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			ids.add(pending(STUB, "ref-c" + i, Instant.now().minusSeconds(600), Instant.now().minusSeconds(20 - i)));
		}
		stub.beforeAnswer((request) -> sleep(Duration.ofMillis(20)));
		CyclicBarrier start = new CyclicBarrier(2);

		List<Future<Integer>> runs = new ArrayList<>();
		for (int instance = 0; instance < 2; instance++) {
			runs.add(executor.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				int claimed = 0;
				Instant deadline = Instant.now().plusSeconds(20);
				while (pendingCount() > 0 && Instant.now().isBefore(deadline)) {
					claimed += reconciler.reconcileDuePayments();
					sleep(Duration.ofMillis(5));
				}
				return claimed;
			}));
		}

		assertThat(runs.get(0).get(30, TimeUnit.SECONDS) + runs.get(1).get(30, TimeUnit.SECONDS)).isEqualTo(20);
		assertThat(stub.allCalls()).hasSize(20).allSatisfy((id, calls) -> assertThat(calls.get()).isEqualTo(1));
		assertThat(ids).allSatisfy((id) -> assertThat(row(id)).containsEntry("status", "SUCCEEDED"));
		verify(emailSender, timeout(10000).times(20)).send(any(), any(), any());
	}

	@Test
	void aPaymentLockedByAnotherInstanceIsSkippedNotWaitedFor() throws Exception {
		UUID locked = pending(STUB, "ref-a", Instant.now().minusSeconds(600), Instant.now().minusSeconds(30));
		UUID second = pending(STUB, "ref-b", Instant.now().minusSeconds(600), Instant.now().minusSeconds(20));
		UUID third = pending(STUB, "ref-c", Instant.now().minusSeconds(600), Instant.now().minusSeconds(10));
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> otherInstance = executor.submit(() -> transactionTemplate.executeWithoutResult((status) -> {
			assertThat(transactionRepository.findDueForStatusCheck(Instant.now(), Set.of(STUB), 1))
				.extracting("id")
				.containsExactly(locked);
			holding.countDown();
			await(release);
		}));
		try {
			assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

			long started = System.nanoTime();
			Future<Integer> run = executor.submit(reconciler::reconcileDuePayments);
			assertThat(run.get(5, TimeUnit.SECONDS)).isEqualTo(2);
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
			assertThat(stub.calls(locked)).isZero();
			assertThat(stub.calls(second)).isEqualTo(1);
			assertThat(stub.calls(third)).isEqualTo(1);
		}
		finally {
			release.countDown();
		}
		otherInstance.get(10, TimeUnit.SECONDS);
		assertThat(reconciler.reconcileDuePayments()).isEqualTo(1);
		assertThat(stub.calls(locked)).isEqualTo(1);
	}

	@Test
	void aDuePendingRefundIsSettledOnTheNextRun() {
		UUID payment = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider)
				VALUES (?, ?, 49.90, 'EUR', 'CARD', 'SUCCEEDED', ?)
				""", payment, DEMO_MERCHANT_ID, STUB);
		UUID refund = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO refunds (id, transaction_id, merchant_id, amount, status, provider_reference, next_check_at)
				VALUES (?, ?, ?, 10.00, 'PENDING', 'ref-r', now() - interval '1 second')
				""", refund, payment, DEMO_MERCHANT_ID);
		UUID notDue = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO refunds (id, transaction_id, merchant_id, amount, status, provider_reference, next_check_at)
				VALUES (?, ?, ?, 5.00, 'PENDING', 'ref-later', now() + interval '10 minutes')
				""", notDue, payment, DEMO_MERCHANT_ID);

		assertThat(reconciler.reconcileDuePayments()).isEqualTo(1);

		assertThat(jdbcTemplate.queryForMap("SELECT status, next_check_at, check_attempts FROM refunds WHERE id = ?", refund))
			.containsEntry("status", "SUCCEEDED")
			.containsEntry("next_check_at", null)
			.containsEntry("check_attempts", 1);
		assertThat(stub.calls(refund)).isEqualTo(1);
		assertThat(stub.calls(notDue)).isZero();
		assertThat(reconciler.reconcileDuePayments()).isZero();
	}

	@Test
	void concurrentRunsNeverCheckOneRefundTwice() throws Exception {
		UUID payment = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider)
				VALUES (?, ?, 100.00, 'EUR', 'CARD', 'SUCCEEDED', ?)
				""", payment, DEMO_MERCHANT_ID, STUB);
		for (int i = 0; i < 20; i++) {
			jdbcTemplate.update("""
					INSERT INTO refunds (id, transaction_id, merchant_id, amount, status, provider_reference, next_check_at)
					VALUES (?, ?, ?, 1.00, 'PENDING', ?, now() - interval '1 minute')
					""", UUID.randomUUID(), payment, DEMO_MERCHANT_ID, "ref-r" + i);
		}
		stub.refundDelay(Duration.ofMillis(20));
		CyclicBarrier start = new CyclicBarrier(2);

		List<Future<Integer>> runs = new ArrayList<>();
		for (int instance = 0; instance < 2; instance++) {
			runs.add(executor.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				int claimed = 0;
				Instant deadline = Instant.now().plusSeconds(20);
				while (pendingRefunds() > 0 && Instant.now().isBefore(deadline)) {
					claimed += reconciler.reconcileDuePayments();
					sleep(Duration.ofMillis(5));
				}
				return claimed;
			}));
		}

		assertThat(runs.get(0).get(30, TimeUnit.SECONDS) + runs.get(1).get(30, TimeUnit.SECONDS)).isEqualTo(20);
		assertThat(stub.allCalls()).hasSize(20).allSatisfy((id, calls) -> assertThat(calls.get()).isEqualTo(1));
	}

	private int pendingRefunds() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM refunds WHERE status = 'PENDING'", Integer.class);
	}

	@Test
	void nothingIsScheduledWhenReconciliationIsDisabled() {
		assertThat(context.getBeansOfType(ReconciliationSchedulingConfig.class)).isEmpty();
		assertThat(context.getBeansOfType(PendingPaymentReconciler.class)).hasSize(1);
	}

	private UUID pending(String provider, String reference, Instant createdAt, Instant nextCheckAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider,
				                          provider_reference, created_at, next_check_at, check_attempts)
				VALUES (?, ?, 49.90, 'EUR', 'CARD', 'PENDING', ?, ?, ?, ?, 0)
				""", id, DEMO_MERCHANT_ID, provider, reference, Timestamp.from(createdAt), Timestamp.from(nextCheckAt));
		return id;
	}

	private Map<String, Object> row(UUID id) {
		return jdbcTemplate.queryForMap("""
				SELECT status, decline_reason, provider_reference, processed_at, next_check_at, check_attempts
				FROM transactions WHERE id = ?
				""", id);
	}

	private int pendingCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE status = 'PENDING'", Integer.class);
	}

	private Instant nextCheck(UUID id) {
		return ((Timestamp) row(id).get("next_check_at")).toInstant();
	}

	private void makeDue(UUID id) {
		jdbcTemplate.update("UPDATE transactions SET next_check_at = now() - interval '1 second' WHERE id = ?", id);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
