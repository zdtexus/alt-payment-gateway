package com.example.payment_service.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentIdempotencyConcurrencyTests {

	private static final String KEY = "order-2002";

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@MockitoBean
	private PaymentProvider paymentProvider;

	private final ExecutorService executor = Executors.newFixedThreadPool(2);

	private final CountDownLatch firstInProvider = new CountDownLatch(1);

	private final CountDownLatch releaseProvider = new CountDownLatch(1);

	@BeforeEach
	void holdFirstRequestInProvider() {
		given(paymentProvider.process(any())).willAnswer((invocation) -> {
			firstInProvider.countDown();
			releaseProvider.await(10, TimeUnit.SECONDS);
			return ProviderResult.approved();
		});
	}

	@AfterEach
	void cleanUp() {
		releaseProvider.countDown();
		executor.shutdownNow();
		transactionRepository.deleteAll();
	}

	@Test
	void concurrentRetriesCreateOnePaymentAndOneProviderCall() throws Exception {
		Future<PaymentOutcome> first = submit(request("49.90"));
		assertThat(firstInProvider.await(10, TimeUnit.SECONDS)).isTrue();
		Future<PaymentOutcome> retry = submit(request("49.90"));
		Thread.sleep(500);
		releaseProvider.countDown();

		List<PaymentOutcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS), retry.get(10, TimeUnit.SECONDS));

		assertThat(outcomes).extracting(PaymentOutcome::replayed).containsExactly(false, true);
		assertThat(outcomes.get(1).payment()).isEqualTo(outcomes.get(0).payment());
		assertThat(transactionRepository.count()).isEqualTo(1);
		then(paymentProvider).should(times(1)).process(any());
	}

	@Test
	void concurrentRequestWithDifferentBodyIsRejected() throws Exception {
		Future<PaymentOutcome> first = submit(request("49.90"));
		assertThat(firstInProvider.await(10, TimeUnit.SECONDS)).isTrue();
		Future<PaymentOutcome> other = submit(request("99.90"));
		Thread.sleep(500);
		releaseProvider.countDown();

		assertThat(first.get(10, TimeUnit.SECONDS).replayed()).isFalse();
		assertThatExceptionOfType(ExecutionException.class).isThrownBy(() -> other.get(10, TimeUnit.SECONDS))
			.withCauseInstanceOf(IdempotencyKeyReusedException.class);
		assertThat(transactionRepository.count()).isEqualTo(1);
		then(paymentProvider).should(times(1)).process(any());
	}

	private Future<PaymentOutcome> submit(CreatePaymentRequest request) {
		Merchant merchant = merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("demo-key-123")).orElseThrow();
		return executor.submit(() -> paymentService.process(merchant, request, KEY));
	}

	private static CreatePaymentRequest request(String amount) {
		return new CreatePaymentRequest(new BigDecimal(amount), "EUR", PaymentMethod.CARD);
	}

}
