package com.example.payment_service.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.PaymentRuleViolationException;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.SimulatedPaymentProvider;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.times;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class PaymentIdempotencyIntegrationTests {

	private static final String KEY = "order-1001";

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@MockitoSpyBean
	private SimulatedPaymentProvider paymentProvider;

	@Autowired
	private ApplicationEvents events;

	private Merchant otherMerchant;

	@AfterEach
	void cleanUp() {
		transactionRepository.deleteAll();
		if (otherMerchant != null) {
			merchantRepository.delete(otherMerchant);
		}
	}

	@Test
	void sameKeyAndBodyReturnsOriginalPaymentWithoutCallingProviderAgain() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());

		PaymentOutcome first = paymentService.process(demoMerchant(), request("49.90", "EUR"), KEY);
		PaymentOutcome retry = paymentService.process(demoMerchant(), request("49.90", "EUR"), KEY);

		assertThat(first.replayed()).isFalse();
		assertThat(retry.replayed()).isTrue();
		assertThat(retry.payment()).isEqualTo(first.payment());
		assertThat(transactionRepository.count()).isEqualTo(1);
		then(paymentProvider).should(times(1)).process(any());
		assertThat(events.stream(PaymentProcessedEvent.class)).hasSize(1);
	}

	@Test
	void replayReturnsStoredDeclineEvenIfProviderWouldNowApprove() {
		willReturn(ProviderResult.declined("Insufficient funds"),
				ProviderResult.approved()).given(paymentProvider).process(any());

		paymentService.process(demoMerchant(), request("49.90", "EUR"), KEY);
		PaymentOutcome retry = paymentService.process(demoMerchant(), request("49.90", "EUR"), KEY);

		assertThat(retry.payment().status()).isEqualTo(TransactionStatus.DECLINED);
		assertThat(retry.payment().declineReason()).isEqualTo("Insufficient funds");
		then(paymentProvider).should(times(1)).process(any());
	}

	@Test
	void equalAmountWrittenDifferentlyIsTheSameRequest() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());

		PaymentOutcome first = paymentService.process(demoMerchant(), request("49.9", "EUR"), KEY);
		PaymentOutcome retry = paymentService.process(demoMerchant(), request("49.90", "EUR"), KEY);

		assertThat(retry.replayed()).isTrue();
		assertThat(retry.payment()).isEqualTo(first.payment());
		assertThat(first.payment().amount()).isEqualTo(new BigDecimal("49.90"));
		assertThat(transactionRepository.count()).isEqualTo(1);
	}

	@Test
	void sameKeyWithDifferentBodyIsRejected() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());
		paymentService.process(demoMerchant(), request("49.90", "EUR"), KEY);

		assertThatExceptionOfType(IdempotencyKeyReusedException.class)
			.isThrownBy(() -> paymentService.process(demoMerchant(), request("99.90", "EUR"), KEY));
		assertThatExceptionOfType(IdempotencyKeyReusedException.class)
			.isThrownBy(() -> paymentService.process(demoMerchant(), request("49.90", "USD"), KEY));

		assertThat(transactionRepository.count()).isEqualTo(1);
		then(paymentProvider).should(times(1)).process(any());
		assertThat(events.stream(PaymentProcessedEvent.class)).hasSize(1);
	}

	@Test
	void keysAreScopedPerMerchant() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());
		otherMerchant = createMerchant();

		PaymentOutcome demo = paymentService.process(demoMerchant(), request("49.90", "EUR"), KEY);
		PaymentOutcome other = paymentService.process(otherMerchant, request("99.90", "EUR"), KEY);

		assertThat(other.replayed()).isFalse();
		assertThat(other.payment().id()).isNotEqualTo(demo.payment().id());
		assertThat(transactionRepository.count()).isEqualTo(2);
		then(paymentProvider).should(times(2)).process(any());
	}

	@Test
	void requestsWithoutKeyAlwaysCreateNewPayments() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());

		PaymentOutcome first = paymentService.process(demoMerchant(), request("49.90", "EUR"), null);
		PaymentOutcome second = paymentService.process(demoMerchant(), request("49.90", "EUR"), null);

		assertThat(first.replayed()).isFalse();
		assertThat(second.replayed()).isFalse();
		assertThat(transactionRepository.count()).isEqualTo(2);
		assertThat(transactionRepository.findAll()).extracting(Transaction::getIdempotencyKey).containsOnlyNulls();
	}

	@Test
	void keyIsStoredWithRequestHash() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());

		PaymentOutcome outcome = paymentService.process(demoMerchant(), request("49.90", "EUR"), KEY);

		Transaction stored = transactionRepository.findById(outcome.payment().id()).orElseThrow();
		assertThat(stored.getIdempotencyKey()).isEqualTo(KEY);
		assertThat(stored.getRequestHash()).isEqualTo(PaymentRequestHasher.hash(request("49.90", "EUR")));
	}

	@Test
	void rejectedRequestDoesNotUseUpTheKey() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());

		assertThatExceptionOfType(PaymentRuleViolationException.class)
			.isThrownBy(() -> paymentService.process(demoMerchant(), request("49.90", "GBP"), KEY));
		PaymentOutcome corrected = paymentService.process(demoMerchant(), request("49.90", "EUR"), KEY);

		assertThat(corrected.replayed()).isFalse();
		assertThat(corrected.payment().status()).isEqualTo(TransactionStatus.SUCCEEDED);
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

	private static CreatePaymentRequest request(String amount, String currency) {
		return new CreatePaymentRequest(new BigDecimal(amount), currency, PaymentMethod.CARD);
	}

}
