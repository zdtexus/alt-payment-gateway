package com.example.payment_service.authorization;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import com.example.payment_service.error.PaymentNotCapturableException;
import com.example.payment_service.error.PaymentNotVoidableException;
import com.example.payment_service.provider.ProviderResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mockingDetails;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CaptureVoidConcurrencyTests {

	private static final int REQUESTS = 8;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private CaptureService captureService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private RefundRepository refundRepository;

	@MockitoSpyBean
	private SimulatedPaymentProvider paymentProvider;

	@AfterEach
	void cleanUp() {
		refundRepository.deleteAll();
		transactionRepository.deleteAll();
	}

	@RepeatedTest(5)
	void exactlyOneOfConcurrentCapturesAndVoidsWins() throws Exception {
		Merchant merchant = merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("demo-key-123")).orElseThrow();
		willReturn(AuthorizationResult.approved("sim_a_" + UUID.randomUUID(),
				Instant.now().plus(7, ChronoUnit.DAYS))).given(paymentProvider).authorize(any());
		UUID id = paymentService.process(merchant,
				new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD, CaptureMethod.MANUAL))
			.id();
		willAnswer((invocation) -> slowApproval()).given(paymentProvider).capture(any());
		willAnswer((invocation) -> slowApproval()).given(paymentProvider).voidAuthorization(any());

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
		List<Future<Object>> results = new ArrayList<>();
		for (int i = 0; i < REQUESTS; i++) {
			boolean capture = i % 2 == 0;
			results.add(pool.submit(() -> {
				start.await();
				try {
					return capture ? captureService.capture(merchant, id, null, null)
							: captureService.voidAuthorization(merchant, id, null);
				}
				catch (PaymentNotCapturableException | PaymentNotVoidableException ex) {
					return ex;
				}
			}));
		}
		start.countDown();
		List<Object> outcomes = new ArrayList<>();
		for (Future<Object> result : results) {
			outcomes.add(result.get(30, TimeUnit.SECONDS));
		}
		pool.shutdown();

		List<PaymentOutcome> wins = outcomes.stream()
			.filter(PaymentOutcome.class::isInstance)
			.map(PaymentOutcome.class::cast)
			.toList();
		assertThat(wins).hasSize(1);
		TransactionStatus winner = wins.get(0).payment().status();
		assertThat(winner).isIn(TransactionStatus.SUCCEEDED, TransactionStatus.VOIDED);
		assertThat(transactionRepository.findById(id).orElseThrow().getStatus()).isEqualTo(winner);
		long providerCalls = mockingDetails(paymentProvider).getInvocations()
			.stream()
			.filter((invocation) -> List.of("capture", "voidAuthorization")
				.contains(invocation.getMethod().getName()))
			.count();
		assertThat(providerCalls).isEqualTo(1);
	}

	private static ProviderResult slowApproval() throws InterruptedException {
		Thread.sleep(50);
		return ProviderResult.approved("sim_" + UUID.randomUUID());
	}

}
