package com.example.payment_service.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PageResponse;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.error.PaymentRuleViolationException;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.PaymentProviderRouter;
import com.example.payment_service.provider.ProviderRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.rules.PaymentRuleValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentServiceTests {

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	private static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final CreatePaymentRequest REQUEST = new CreatePaymentRequest(new BigDecimal("49.90"), "EUR",
			PaymentMethod.CARD);

	private final TransactionRepository transactionRepository = mock();

	private final PaymentRuleValidator ruleValidator = mock();

	private final PaymentProvider paymentProvider = mock();

	private final PaymentProviderRouter providerRouter = mock();

	private final ApplicationEventPublisher eventPublisher = mock();

	private final PlatformTransactionManager transactionManager = mock();

	private final PaymentService paymentService = new PaymentService(transactionRepository, ruleValidator,
			providerRouter, eventPublisher, new TransactionTemplate(transactionManager));

	private final Merchant merchant = new Merchant();

	private final AtomicReference<TransactionStatus> statusWhenSaved = new AtomicReference<>();

	private final AtomicReference<String> providerWhenSaved = new AtomicReference<>();

	@BeforeEach
	void assignIdOnSave() {
		given(providerRouter.providerFor(any())).willReturn(paymentProvider);
		given(paymentProvider.name()).willReturn("simulated");
		given(transactionRepository.saveAndFlush(any())).willAnswer((invocation) -> {
			Transaction transaction = invocation.getArgument(0);
			statusWhenSaved.set(transaction.getStatus());
			providerWhenSaved.set(transaction.getProvider());
			transaction.setId(PAYMENT_ID);
			given(transactionRepository.findById(PAYMENT_ID)).willReturn(Optional.of(transaction));
			return transaction;
		});
	}

	@Test
	void approvedPaymentSucceeds() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());
		Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

		PaymentResponse response = paymentService.process(merchant, REQUEST);

		assertThat(response.id()).isEqualTo(PAYMENT_ID);
		assertThat(response.status()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(response.declineReason()).isNull();
		assertThat(response.processedAt()).isBetween(before, Instant.now());
		assertThat(response.amount()).isEqualByComparingTo("49.90");
		assertThat(response.currency()).isEqualTo("EUR");
		assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.CARD);
	}

	@Test
	void declinedPaymentKeepsReason() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.declined("Insufficient funds"));

		PaymentResponse response = paymentService.process(merchant, REQUEST);

		assertThat(response.status()).isEqualTo(TransactionStatus.DECLINED);
		assertThat(response.declineReason()).isEqualTo("Insufficient funds");
		assertThat(response.processedAt()).isNotNull();
	}

	@Test
	void pendingPaymentStaysPendingWithReferenceAndNoEvent() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.pending("ref-789"));

		PaymentResponse response = paymentService.process(merchant, REQUEST);

		assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);
		assertThat(response.declineReason()).isNull();
		assertThat(response.processedAt()).isNull();
		Transaction saved = savedTransaction();
		assertThat(saved.getStatus()).isEqualTo(TransactionStatus.PENDING);
		assertThat(saved.getProviderReference()).isEqualTo("ref-789");
		assertThat(saved.getProcessedAt()).isNull();
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void savesPendingTransactionForMerchantBeforeCallingProvider() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());

		paymentService.process(merchant, REQUEST);

		ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
		verify(transactionRepository).saveAndFlush(saved.capture());
		assertThat(statusWhenSaved).hasValue(TransactionStatus.PENDING);
		assertThat(saved.getValue().getMerchant()).isSameAs(merchant);
		verify(paymentProvider)
			.process(new ProviderRequest(PAYMENT_ID, PaymentMethod.CARD, "EUR", new BigDecimal("49.90")));
	}

	@Test
	void storesProviderNameOnThePendingTransaction() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());

		paymentService.process(merchant, REQUEST);

		assertThat(providerWhenSaved).hasValue("simulated");
	}

	@Test
	void storesProviderReferenceOfApprovedPayment() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved("ref-123"));

		paymentService.process(merchant, REQUEST);

		assertThat(savedTransaction().getProviderReference()).isEqualTo("ref-123");
	}

	@Test
	void storesProviderReferenceOfDeclinedPayment() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.declined("Insufficient funds", "ref-456"));

		paymentService.process(merchant, REQUEST);

		Transaction saved = savedTransaction();
		assertThat(saved.getProviderReference()).isEqualTo("ref-456");
		assertThat(saved.getDeclineReason()).isEqualTo("Insufficient funds");
	}

	@Test
	void resultWithoutReferenceLeavesItEmpty() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());

		paymentService.process(merchant, REQUEST);

		assertThat(savedTransaction().getProviderReference()).isNull();
	}

	@Test
	void asksRouterForTheProviderOfThePaymentMethod() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());

		paymentService.process(merchant,
				new CreatePaymentRequest(new BigDecimal("20.00"), "EUR", PaymentMethod.KVIK));

		verify(providerRouter).providerFor(PaymentMethod.KVIK);
	}

	@Test
	void publishesProcessedEventWithMerchantContacts() {
		merchant.setName("Demo Shop");
		merchant.setEmail("shop@example.com");
		merchant.setPhone("+380501234567");
		given(paymentProvider.process(any())).willReturn(ProviderResult.declined("Insufficient funds"));

		PaymentResponse response = paymentService.process(merchant, REQUEST);

		verify(eventPublisher).publishEvent(new PaymentProcessedEvent(PAYMENT_ID, "Demo Shop", "shop@example.com",
				"+380501234567", new BigDecimal("49.90"), "EUR", PaymentMethod.CARD, "simulated",
				TransactionStatus.DECLINED, "Insufficient funds", response.processedAt()));
	}

	@Test
	void providerFailureLeavesPaymentPendingAndPublishesNothing() {
		given(paymentProvider.process(any())).willThrow(new IllegalStateException("Provider unreachable"));

		PaymentResponse response = paymentService.process(merchant, REQUEST);

		assertThat(response.id()).isEqualTo(PAYMENT_ID);
		assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);
		assertThat(response.processedAt()).isNull();
		assertThat(savedTransaction().getStatus()).isEqualTo(TransactionStatus.PENDING);
		verify(transactionManager, times(1)).commit(any());
		verify(transactionRepository, never()).findById(any());
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void callsProviderBetweenTwoTransactions() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());

		paymentService.process(merchant, REQUEST);

		InOrder order = inOrder(transactionManager, transactionRepository, paymentProvider, eventPublisher);
		order.verify(transactionManager).getTransaction(any());
		order.verify(transactionRepository).saveAndFlush(any());
		order.verify(transactionManager).commit(any());
		order.verify(paymentProvider).process(any());
		order.verify(transactionManager).getTransaction(any());
		order.verify(transactionRepository).findById(PAYMENT_ID);
		order.verify(eventPublisher).publishEvent(any(Object.class));
		order.verify(transactionManager).commit(any());
	}

	@Test
	void getReturnsMerchantsOwnPayment() {
		merchant.setId(MERCHANT_ID);
		Transaction transaction = transaction();
		given(transactionRepository.findByIdAndMerchantId(PAYMENT_ID, MERCHANT_ID))
			.willReturn(Optional.of(transaction));

		assertThat(paymentService.get(merchant, PAYMENT_ID).id()).isEqualTo(PAYMENT_ID);
	}

	@Test
	void getThrowsWhenPaymentIsNotMerchantsOwn() {
		merchant.setId(MERCHANT_ID);
		given(transactionRepository.findByIdAndMerchantId(PAYMENT_ID, MERCHANT_ID)).willReturn(Optional.empty());

		assertThatExceptionOfType(PaymentNotFoundException.class)
			.isThrownBy(() -> paymentService.get(merchant, PAYMENT_ID))
			.withMessage("Payment " + PAYMENT_ID + " not found");
	}

	@Test
	void listWithoutStatusReturnsAllNewestFirst() {
		merchant.setId(MERCHANT_ID);
		given(transactionRepository.findByMerchantId(eq(MERCHANT_ID), any()))
			.willReturn(new PageImpl<>(List.of(transaction()), PageRequest.of(1, 10), 11));

		PageResponse<PaymentResponse> page = paymentService.list(merchant, null, 1, 10);

		assertThat(page.content()).extracting(PaymentResponse::id).containsExactly(PAYMENT_ID);
		assertThat(page.totalElements()).isEqualTo(11);
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(transactionRepository).findByMerchantId(eq(MERCHANT_ID), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
		assertThat(pageable.getValue().getSort())
			.isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
	}

	@Test
	void listWithStatusFiltersByStatus() {
		merchant.setId(MERCHANT_ID);
		given(transactionRepository.findByMerchantIdAndStatus(eq(MERCHANT_ID), eq(TransactionStatus.DECLINED), any()))
			.willReturn(Page.empty());

		paymentService.list(merchant, TransactionStatus.DECLINED, 0, 20);

		verify(transactionRepository).findByMerchantIdAndStatus(eq(MERCHANT_ID), eq(TransactionStatus.DECLINED),
				any());
		verify(transactionRepository, never()).findByMerchantId(any(), any());
	}

	@Test
	void ruleViolationStopsBeforeSavingOrCallingProvider() {
		willThrow(new PaymentRuleViolationException("Amount 49.90 exceeds BONUS limit 500.00")).given(ruleValidator)
			.validate(PaymentMethod.CARD, "EUR", new BigDecimal("49.90"));

		assertThatExceptionOfType(PaymentRuleViolationException.class)
			.isThrownBy(() -> paymentService.process(merchant, REQUEST));
		verifyNoInteractions(transactionRepository, providerRouter, paymentProvider, eventPublisher);
	}

	private static Transaction transaction() {
		Transaction transaction = new Transaction();
		transaction.setId(PAYMENT_ID);
		transaction.setAmount(new BigDecimal("49.90"));
		transaction.setCurrency("EUR");
		transaction.setPaymentMethod(PaymentMethod.CARD);
		transaction.setStatus(TransactionStatus.SUCCEEDED);
		return transaction;
	}

	private Transaction savedTransaction() {
		ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
		verify(transactionRepository).saveAndFlush(saved.capture());
		return saved.getValue();
	}

}
