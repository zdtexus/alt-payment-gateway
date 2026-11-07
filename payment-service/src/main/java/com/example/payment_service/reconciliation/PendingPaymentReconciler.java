package com.example.payment_service.reconciliation;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.payment_service.entity.Refund;
import com.example.payment_service.entity.RefundStatus;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.provider.PaymentProviderRegistry;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.ProviderStatusRequest;
import com.example.payment_service.provider.RefundStatusCheckingProvider;
import com.example.payment_service.provider.RefundStatusRequest;
import com.example.payment_service.provider.StatusCheckingProvider;
import com.example.payment_service.repository.RefundRepository;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.PaymentService;
import com.example.payment_service.service.RefundService;

@Component
public class PendingPaymentReconciler {

	private static final Logger log = LoggerFactory.getLogger(PendingPaymentReconciler.class);

	private static final int MAX_DOUBLINGS = 30;

	private final TransactionRepository transactionRepository;

	private final PaymentProviderRegistry providerRegistry;

	private final PaymentService paymentService;

	private final TransactionTemplate transactionTemplate;

	private final ReconciliationProperties properties;

	private final RefundRepository refundRepository;

	private final RefundService refundService;

	public PendingPaymentReconciler(TransactionRepository transactionRepository,
			PaymentProviderRegistry providerRegistry, PaymentService paymentService,
			TransactionTemplate transactionTemplate, ReconciliationProperties properties,
			RefundRepository refundRepository, RefundService refundService) {
		this.transactionRepository = transactionRepository;
		this.providerRegistry = providerRegistry;
		this.paymentService = paymentService;
		this.transactionTemplate = transactionTemplate;
		this.properties = properties;
		this.refundRepository = refundRepository;
		this.refundService = refundService;
	}

	public void runScheduled() {
		try {
			reconcileDuePayments();
		}
		catch (RuntimeException ex) {
			log.error("Pending payment reconciliation failed; it runs again in {}", properties.interval(), ex);
		}
	}

	public int reconcileDuePayments() {
		return reconcilePayments() + reconcileRefunds();
	}

	private int reconcilePayments() {
		Map<String, StatusCheckingProvider> providers = providerRegistry.statusCheckingProviders();
		if (providers.isEmpty()) {
			return 0;
		}
		List<ClaimedPayment> claimed = claim(providers);
		int settled = 0;
		int stillPending = 0;
		int failed = 0;
		for (ClaimedPayment payment : claimed) {
			switch (check(payment, providers.get(payment.provider()))) {
				case SETTLED -> settled++;
				case STILL_PENDING -> stillPending++;
				case FAILED -> failed++;
			}
		}
		if (!claimed.isEmpty()) {
			log.info("Checked {} pending payments with their providers: {} settled, {} still pending, {} failed",
					claimed.size(), settled, stillPending, failed);
		}
		return claimed.size();
	}

	private int reconcileRefunds() {
		Map<String, RefundStatusCheckingProvider> providers = providerRegistry.refundStatusCheckingProviders();
		if (providers.isEmpty()) {
			return 0;
		}
		List<ClaimedRefund> claimed = claimRefunds(providers);
		int settled = 0;
		int stillPending = 0;
		int failed = 0;
		for (ClaimedRefund refund : claimed) {
			switch (checkRefund(refund, providers.get(refund.provider()))) {
				case SETTLED -> settled++;
				case STILL_PENDING -> stillPending++;
				case FAILED -> failed++;
			}
		}
		if (!claimed.isEmpty()) {
			log.info("Checked {} pending refunds with their providers: {} settled, {} still pending, {} failed",
					claimed.size(), settled, stillPending, failed);
		}
		return claimed.size();
	}

	private List<ClaimedRefund> claimRefunds(Map<String, RefundStatusCheckingProvider> providers) {
		return transactionTemplate.execute((status) -> {
			Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
			List<Refund> due = refundRepository.findDueForStatusCheck(now, providers.keySet(), properties.batchSize());
			return due.stream().map((refund) -> {
				int attempts = refund.getCheckAttempts();
				refund.setNextCheckAt(now.plus(backoff(attempts, properties.interval(), properties.maxBackoff())));
				refund.setCheckAttempts(attempts + 1);
				Transaction payment = refund.getTransaction();
				return new ClaimedRefund(refund.getId(), payment.getId(), payment.getProvider(),
						refund.getProviderReference(), refund.getCreatedAt(), attempts + 1);
			}).toList();
		});
	}

	private Outcome checkRefund(ClaimedRefund refund, RefundStatusCheckingProvider provider) {
		Outcome outcome;
		try {
			ProviderResult result = provider
				.checkRefundStatus(new RefundStatusRequest(refund.id(), refund.paymentId(), refund.reference()));
			RefundStatus status = refundService.settleFromStatusCheck(refund.id(), result);
			outcome = (status == RefundStatus.PENDING) ? Outcome.STILL_PENDING : Outcome.SETTLED;
		}
		catch (RuntimeException ex) {
			log.warn("Status check {} for refund {} of payment {} with provider {} failed; next check after backoff: {}",
					refund.attempt(), refund.id(), refund.paymentId(), refund.provider(), ex.toString());
			outcome = Outcome.FAILED;
		}
		if (outcome != Outcome.SETTLED && refund.createdAt().plus(properties.alertAfter()).isBefore(Instant.now())) {
			log.error("Refund {} of payment {} with provider {} is still PENDING {} after it was created (check {}); "
					+ "check it with the provider", refund.id(), refund.paymentId(), refund.provider(),
					Duration.between(refund.createdAt(), Instant.now()).truncatedTo(ChronoUnit.MINUTES),
					refund.attempt());
		}
		return outcome;
	}

	static Duration backoff(int attempts, Duration interval, Duration maxBackoff) {
		Duration delay = interval.multipliedBy(1L << Math.min(Math.max(attempts, 0), MAX_DOUBLINGS));
		return (delay.compareTo(maxBackoff) > 0) ? maxBackoff : delay;
	}

	private List<ClaimedPayment> claim(Map<String, StatusCheckingProvider> providers) {
		return transactionTemplate.execute((status) -> {
			Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
			List<Transaction> due = transactionRepository.findDueForStatusCheck(now, providers.keySet(),
					properties.batchSize());
			return due.stream().map((transaction) -> {
				int attempts = transaction.getCheckAttempts();
				transaction.setNextCheckAt(now.plus(backoff(attempts, properties.interval(), properties.maxBackoff())));
				transaction.setCheckAttempts(attempts + 1);
				return new ClaimedPayment(transaction.getId(), transaction.getProvider(),
						transaction.getProviderReference(), transaction.getCreatedAt(), attempts + 1);
			}).toList();
		});
	}

	private Outcome check(ClaimedPayment payment, StatusCheckingProvider provider) {
		Outcome outcome;
		try {
			ProviderResult result = provider.checkStatus(new ProviderStatusRequest(payment.id(), payment.reference()));
			TransactionStatus status = paymentService.settleFromStatusCheck(payment.id(), result);
			outcome = (status == TransactionStatus.PENDING) ? Outcome.STILL_PENDING : Outcome.SETTLED;
		}
		catch (RuntimeException ex) {
			log.warn("Status check {} for payment {} with provider {} failed; next check after backoff: {}",
					payment.attempt(), payment.id(), payment.provider(), ex.toString());
			outcome = Outcome.FAILED;
		}
		if (outcome != Outcome.SETTLED && isOverdue(payment)) {
			log.error("Payment {} with provider {} is still PENDING {} after it was created (check {}); "
					+ "check it with the provider", payment.id(), payment.provider(),
					Duration.between(payment.createdAt(), Instant.now()).truncatedTo(ChronoUnit.MINUTES),
					payment.attempt());
		}
		return outcome;
	}

	private boolean isOverdue(ClaimedPayment payment) {
		return payment.createdAt().plus(properties.alertAfter()).isBefore(Instant.now());
	}

	private enum Outcome {

		SETTLED, STILL_PENDING, FAILED

	}

	record ClaimedPayment(UUID id, String provider, String reference, Instant createdAt, int attempt) {
	}

	record ClaimedRefund(UUID id, UUID paymentId, String provider, String reference, Instant createdAt, int attempt) {
	}

}
