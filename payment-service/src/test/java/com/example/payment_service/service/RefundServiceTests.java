package com.example.payment_service.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Refund;
import com.example.payment_service.entity.RefundStatus;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.error.PaymentNotRefundableException;
import com.example.payment_service.error.RefundExceedsRemainingException;
import com.example.payment_service.error.RefundNotSupportedException;
import com.example.payment_service.provider.PaymentProviderRegistry;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.RefundRequest;
import com.example.payment_service.provider.RefundingProvider;
import com.example.payment_service.reconciliation.ReconciliationProperties;
import com.example.payment_service.repository.RefundRepository;
import com.example.payment_service.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(OutputCaptureExtension.class)
class RefundServiceTests {

	private static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	private static final UUID REFUND_ID = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

	private static final Duration MIN_AGE = Duration.ofMinutes(2);

	private final TransactionRepository transactionRepository = mock();

	private final RefundRepository refundRepository = mock();

	private final PaymentProviderRegistry providerRegistry = mock();

	private final PlatformTransactionManager transactionManager = mock();

	private final RefundingProvider provider = mock();

	private final RefundService refundService = new RefundService(transactionRepository, refundRepository,
			providerRegistry, new TransactionTemplate(transactionManager), new ReconciliationProperties(true,
					Duration.ofMinutes(1), MIN_AGE, 50, Duration.ofHours(1), Duration.ofHours(24)));

	private final Merchant merchant = new Merchant();

	private final Transaction payment = new Transaction();

	@BeforeEach
	void succeededPaymentOfFiftyEuro() {
		merchant.setId(MERCHANT_ID);
		payment.setId(PAYMENT_ID);
		payment.setMerchant(merchant);
		payment.setAmount(new BigDecimal("50.00"));
		payment.setCurrency("EUR");
		payment.setPaymentMethod(PaymentMethod.CARD);
		payment.setStatus(TransactionStatus.SUCCEEDED);
		payment.setProvider("simulated");
		payment.setProviderReference("sim_p");
		given(transactionRepository.findLockedByIdAndMerchantId(PAYMENT_ID, MERCHANT_ID))
			.willReturn(Optional.of(payment));
		given(providerRegistry.findRefundingProvider("simulated")).willReturn(Optional.of(provider));
		given(refundRepository.sumAmount(eq(PAYMENT_ID), any())).willReturn(new BigDecimal("0.00"));
		given(refundRepository.saveAndFlush(any())).willAnswer((invocation) -> {
			Refund refund = invocation.getArgument(0);
			refund.setId(REFUND_ID);
			given(refundRepository.findLockedById(REFUND_ID)).willReturn(Optional.of(refund));
			return refund;
		});
		given(provider.refund(any())).willReturn(ProviderResult.approved("sim_r_1"));
	}

	@Test
	void refundsTheRemainingAmountByDefault() {
		given(refundRepository.sumAmount(eq(PAYMENT_ID), any())).willReturn(new BigDecimal("20.00"));

		RefundOutcome outcome = refundService.create(merchant, PAYMENT_ID, null, null, null);

		assertThat(outcome.replayed()).isFalse();
		assertThat(outcome.refund().amount()).isEqualTo(new BigDecimal("30.00"));
		assertThat(outcome.refund().status()).isEqualTo(RefundStatus.SUCCEEDED);
		assertThat(outcome.refund().paymentId()).isEqualTo(PAYMENT_ID);
		assertThat(outcome.refund().currency()).isEqualTo("EUR");
		assertThat(outcome.refund().processedAt()).isNotNull();
	}

	@Test
	void sendsThePartialAmountWithThePaymentsReferenceToItsProvider() {
		refundService.create(merchant, PAYMENT_ID, new BigDecimal("10"), "  Damaged item  ", null);

		ArgumentCaptor<RefundRequest> request = ArgumentCaptor.forClass(RefundRequest.class);
		verify(provider).refund(request.capture());
		assertThat(request.getValue()).isEqualTo(
				new RefundRequest(REFUND_ID, PAYMENT_ID, "sim_p", "EUR", new BigDecimal("10.00")));
		Refund saved = savedRefund();
		assertThat(saved.getReason()).isEqualTo("Damaged item");
		assertThat(saved.getMerchant()).isSameAs(merchant);
		assertThat(saved.getTransaction()).isSameAs(payment);
	}

	@Test
	void pendingAndSucceededRefundsReserveTheAmount() {
		refundService.create(merchant, PAYMENT_ID, new BigDecimal("1.00"), null, null);

		verify(refundRepository).sumAmount(PAYMENT_ID, EnumSet.of(RefundStatus.PENDING, RefundStatus.SUCCEEDED));
	}

	@Test
	void moreThanTheRemainingAmountIsRejectedBeforeAnythingIsStored() {
		given(refundRepository.sumAmount(eq(PAYMENT_ID), any())).willReturn(new BigDecimal("40.00"));

		assertThatExceptionOfType(RefundExceedsRemainingException.class)
			.isThrownBy(() -> refundService.create(merchant, PAYMENT_ID, new BigDecimal("10.01"), null, null))
			.withMessage("Refund 10.01 exceeds the remaining refundable amount 10.00");

		verify(refundRepository, never()).saveAndFlush(any());
		verifyNoInteractions(provider);
	}

	@Test
	void exactlyTheRemainingAmountIsAllowed() {
		given(refundRepository.sumAmount(eq(PAYMENT_ID), any())).willReturn(new BigDecimal("40.00"));

		assertThat(refundService.create(merchant, PAYMENT_ID, new BigDecimal("10.00"), null, null).refund().amount())
			.isEqualTo(new BigDecimal("10.00"));
	}

	@Test
	void aFullyRefundedPaymentHasNothingLeft() {
		given(refundRepository.sumAmount(eq(PAYMENT_ID), any())).willReturn(new BigDecimal("50.00"));

		assertThatExceptionOfType(RefundExceedsRemainingException.class)
			.isThrownBy(() -> refundService.create(merchant, PAYMENT_ID, null, null, null))
			.withMessage("Payment is already fully refunded");
		verify(refundRepository, never()).saveAndFlush(any());
	}

	@Test
	void anotherMerchantsOrUnknownPaymentIsNotFound() {
		UUID other = UUID.randomUUID();
		given(transactionRepository.findLockedByIdAndMerchantId(other, MERCHANT_ID)).willReturn(Optional.empty());

		assertThatExceptionOfType(PaymentNotFoundException.class)
			.isThrownBy(() -> refundService.create(merchant, other, null, null, null))
			.withMessage("Payment " + other + " not found");
		verify(refundRepository, never()).saveAndFlush(any());
	}

	@ParameterizedTest
	@EnumSource(value = TransactionStatus.class, names = { "PENDING", "DECLINED" })
	void onlySucceededPaymentsCanBeRefunded(TransactionStatus status) {
		payment.setStatus(status);

		assertThatExceptionOfType(PaymentNotRefundableException.class)
			.isThrownBy(() -> refundService.create(merchant, PAYMENT_ID, null, null, null))
			.withMessage("Payment " + PAYMENT_ID + " is " + status + "; only SUCCEEDED payments can be refunded");
		verify(refundRepository, never()).saveAndFlush(any());
	}

	@Test
	void aProviderWithoutRefundsIsRejected() {
		given(providerRegistry.findRefundingProvider("simulated")).willReturn(Optional.empty());

		assertThatExceptionOfType(RefundNotSupportedException.class)
			.isThrownBy(() -> refundService.create(merchant, PAYMENT_ID, null, null, null));
		verify(refundRepository, never()).saveAndFlush(any());
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1.00", "1.005" })
	void invalidAmountIsRejectedBeforeTheDatabase(BigDecimal amount) {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> refundService.create(merchant, PAYMENT_ID, amount, null, null))
			.withMessage("Refund amount must be greater than 0 with at most 2 decimals");
		verifyNoInteractions(transactionRepository, refundRepository, transactionManager);
	}

	@Test
	void providerIsCalledBetweenTwoTransactions() {
		refundService.create(merchant, PAYMENT_ID, null, null, null);

		InOrder order = inOrder(transactionManager, transactionRepository, refundRepository, provider);
		order.verify(transactionManager).getTransaction(any());
		order.verify(transactionRepository).findLockedByIdAndMerchantId(PAYMENT_ID, MERCHANT_ID);
		order.verify(refundRepository).saveAndFlush(any());
		order.verify(transactionManager).commit(any());
		order.verify(provider).refund(any());
		order.verify(transactionManager).getTransaction(any());
		order.verify(refundRepository).findLockedById(REFUND_ID);
		order.verify(transactionManager).commit(any());
	}

	@Test
	void newRefundIsPendingWithItsFirstCheckScheduled() {
		given(provider.refund(any())).willReturn(ProviderResult.pending("sim_r_p"));

		RefundOutcome outcome = refundService.create(merchant, PAYMENT_ID, null, null, null);

		Refund saved = savedRefund();
		assertThat(outcome.refund().status()).isEqualTo(RefundStatus.PENDING);
		assertThat(saved.getProviderReference()).isEqualTo("sim_r_p");
		assertThat(saved.getNextCheckAt()).isEqualTo(saved.getCreatedAt().plus(MIN_AGE));
		assertThat(saved.getProcessedAt()).isNull();
	}

	@Test
	void declinedRefundKeepsTheReasonAndIsFinal() {
		given(provider.refund(any())).willReturn(ProviderResult.declined("Card account closed", "sim_r_d"));

		RefundOutcome outcome = refundService.create(merchant, PAYMENT_ID, null, null, null);

		assertThat(outcome.refund().status()).isEqualTo(RefundStatus.DECLINED);
		assertThat(outcome.refund().declineReason()).isEqualTo("Card account closed");
		Refund saved = savedRefund();
		assertThat(saved.getNextCheckAt()).isNull();
		assertThat(saved.getProcessedAt()).isNotNull();
	}

	@Test
	void providerFailureLeavesTheRefundPending(CapturedOutput output) {
		given(provider.refund(any())).willThrow(new IllegalStateException("provider down"));

		RefundOutcome outcome = refundService.create(merchant, PAYMENT_ID, null, null, null);

		assertThat(outcome.refund().status()).isEqualTo(RefundStatus.PENDING);
		verify(refundRepository, never()).findLockedById(any());
		assertThat(output).contains("Refund " + REFUND_ID + " of payment " + PAYMENT_ID
				+ " failed at its provider; the refund stays PENDING");
	}

	@Test
	void answerForARefundSettledMeanwhileIsIgnored(CapturedOutput output) {
		Refund settled = new Refund();
		settled.setId(REFUND_ID);
		settled.setTransaction(payment);
		settled.setAmount(new BigDecimal("50.00"));
		settled.setStatus(RefundStatus.DECLINED);
		settled.setDeclineReason("Refund window expired");
		willAnswer((invocation) -> {
			Refund refund = invocation.getArgument(0);
			refund.setId(REFUND_ID);
			return refund;
		}).given(refundRepository).saveAndFlush(any());
		given(refundRepository.findLockedById(REFUND_ID)).willReturn(Optional.of(settled));

		RefundOutcome outcome = refundService.create(merchant, PAYMENT_ID, null, null, null);

		assertThat(outcome.refund().status()).isEqualTo(RefundStatus.DECLINED);
		assertThat(settled.getDeclineReason()).isEqualTo("Refund window expired");
		assertThat(output).contains("Refund " + REFUND_ID + " was already DECLINED when its provider answered");
	}

	@Test
	void sameKeyAndRequestReplaysTheStoredRefundWithoutCallingTheProvider() {
		Refund existing = existingRefund(RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("10.00"), null));
		given(refundRepository.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, "refund-1"))
			.willReturn(Optional.of(existing));

		RefundOutcome outcome = refundService.create(merchant, PAYMENT_ID, new BigDecimal("10"), null, "refund-1");

		assertThat(outcome.replayed()).isTrue();
		assertThat(outcome.refund().id()).isEqualTo(REFUND_ID);
		verifyNoInteractions(provider);
		verify(refundRepository, never()).saveAndFlush(any());
	}

	@Test
	void sameKeyWithAnotherRequestIsRejected() {
		Refund existing = existingRefund(RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("10.00"), null));
		given(refundRepository.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, "refund-1"))
			.willReturn(Optional.of(existing));

		assertThatExceptionOfType(IdempotencyKeyReusedException.class)
			.isThrownBy(() -> refundService.create(merchant, PAYMENT_ID, new BigDecimal("11.00"), null, "refund-1"));
		verifyNoInteractions(provider);
	}

	@Test
	void raceOnTheSameKeyReplaysTheWinner() {
		String hash = RefundRequestHasher.hash(PAYMENT_ID, null, null);
		given(refundRepository.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, "refund-2"))
			.willReturn(Optional.empty())
			.willReturn(Optional.of(existingRefund(hash)));
		willThrow(new DataIntegrityViolationException("duplicate key")).given(refundRepository).saveAndFlush(any());

		RefundOutcome outcome = refundService.create(merchant, PAYMENT_ID, null, null, "refund-2");

		assertThat(outcome.replayed()).isTrue();
		assertThat(outcome.refund().id()).isEqualTo(REFUND_ID);
		verifyNoInteractions(provider);
	}

	@Test
	void keyIsStoredWithTheRequestHash() {
		refundService.create(merchant, PAYMENT_ID, new BigDecimal("5.00"), "Late delivery", "refund-3");

		Refund saved = savedRefund();
		assertThat(saved.getIdempotencyKey()).isEqualTo("refund-3");
		assertThat(saved.getRequestHash())
			.isEqualTo(RefundRequestHasher.hash(PAYMENT_ID, new BigDecimal("5.00"), "Late delivery"));
	}

	@Test
	void listsThePaymentsRefundsWithItsCurrency() {
		given(transactionRepository.findByIdAndMerchantId(PAYMENT_ID, MERCHANT_ID)).willReturn(Optional.of(payment));
		given(refundRepository.findByTransactionIdOrderByCreatedAtAscIdAsc(PAYMENT_ID))
			.willReturn(java.util.List.of(existingRefund("h")));

		assertThat(refundService.list(merchant, PAYMENT_ID)).singleElement().satisfies((refund) -> {
			assertThat(refund.id()).isEqualTo(REFUND_ID);
			assertThat(refund.paymentId()).isEqualTo(PAYMENT_ID);
			assertThat(refund.currency()).isEqualTo("EUR");
		});
	}

	@Test
	void listingAnotherMerchantsPaymentIsNotFound() {
		UUID other = UUID.randomUUID();
		given(transactionRepository.findByIdAndMerchantId(other, MERCHANT_ID)).willReturn(Optional.empty());

		assertThatExceptionOfType(PaymentNotFoundException.class).isThrownBy(() -> refundService.list(merchant, other));
		verify(refundRepository, never()).findByTransactionIdOrderByCreatedAtAscIdAsc(any());
	}

	@Test
	void getsOneRefundOfThePaymentOnly() {
		given(transactionRepository.findByIdAndMerchantId(PAYMENT_ID, MERCHANT_ID)).willReturn(Optional.of(payment));
		given(refundRepository.findByIdAndTransactionId(REFUND_ID, PAYMENT_ID))
			.willReturn(Optional.of(existingRefund("h")));
		UUID otherRefund = UUID.randomUUID();
		given(refundRepository.findByIdAndTransactionId(otherRefund, PAYMENT_ID)).willReturn(Optional.empty());

		assertThat(refundService.get(merchant, PAYMENT_ID, REFUND_ID).id()).isEqualTo(REFUND_ID);
		assertThatExceptionOfType(com.example.payment_service.error.RefundNotFoundException.class)
			.isThrownBy(() -> refundService.get(merchant, PAYMENT_ID, otherRefund))
			.withMessage("Refund " + otherRefund + " not found");
	}

	private Refund existingRefund(String requestHash) {
		Refund refund = new Refund();
		refund.setId(REFUND_ID);
		refund.setTransaction(payment);
		refund.setMerchant(merchant);
		refund.setAmount(new BigDecimal("10.00"));
		refund.setStatus(RefundStatus.SUCCEEDED);
		refund.setIdempotencyKey("refund-1");
		refund.setRequestHash(requestHash);
		return refund;
	}

	private Refund savedRefund() {
		ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
		verify(refundRepository).saveAndFlush(saved.capture());
		return saved.getValue();
	}

}
