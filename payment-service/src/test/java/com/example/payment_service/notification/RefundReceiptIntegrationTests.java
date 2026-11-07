package com.example.payment_service.notification;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.RefundStatus;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.ProviderWebhook;
import com.example.payment_service.provider.SimulatedPaymentProvider;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.service.RefundOutcome;
import com.example.payment_service.service.RefundService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = { "PAYMENT_PROVIDER_SUCCESS_RATE=1.0", "PAYMENT_PROVIDER_LATENCY=0ms" })
class RefundReceiptIntegrationTests {

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	private RefundService refundService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoSpyBean
	private SimulatedPaymentProvider simulatedProvider;

	@MockitoBean
	private EmailSender emailSender;

	@MockitoBean
	private SmsSender smsSender;

	private Merchant merchant;

	private UUID payment;

	@BeforeEach
	void succeededPayment() {
		merchant = merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow();
		payment = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider)
				VALUES (?, ?, 50.00, 'EUR', 'CARD', 'SUCCEEDED', 'simulated')
				""", payment, DEMO_MERCHANT_ID);
	}

	@Test
	void aSucceededRefundSendsOneEmailAndOneSmsAfterCommit() {
		AtomicReference<String> senderThread = new AtomicReference<>();
		willAnswer((invocation) -> {
			senderThread.set(Thread.currentThread().getName());
			return null;
		}).given(emailSender).send(any(), any(), any());

		RefundOutcome refund = refundService.create(merchant, payment, new BigDecimal("10.00"), null, null);

		verify(emailSender, timeout(5000)).send(eq("shop@demo.local"), eq("Refund " + refund.refund().id() + " succeeded"),
				contains("Refund " + refund.refund().id() + " of payment " + payment + " succeeded."));
		verify(smsSender, timeout(5000)).send("+380501112233", "Refund 10.00 EUR succeeded");
		verify(emailSender, after(1000).times(1)).send(any(), any(), any());
		verify(smsSender, times(1)).send(any(), any());
		assertThat(senderThread.get()).isNotEqualTo(Thread.currentThread().getName());
	}

	@Test
	void aDeclinedRefundSendsItsReason() {
		willReturn(ProviderResult.declined("Card account closed", "sim_r_d")).given(simulatedProvider).refund(any());

		refundService.create(merchant, payment, new BigDecimal("10.00"), null, null);

		verify(emailSender, timeout(5000)).send(any(), contains("declined"), contains("Reason: Card account closed"));
		verify(smsSender, timeout(5000)).send("+380501112233", "Refund 10.00 EUR declined");
	}

	@Test
	void aPendingRefundSendsNothingUntilItsWebhookAndThenExactlyOnce() {
		willReturn(ProviderResult.pending("sim_r_wait")).given(simulatedProvider).refund(any());

		RefundOutcome refund = refundService.create(merchant, payment, new BigDecimal("10.00"), null, null);
		assertThat(refund.refund().status()).isEqualTo(RefundStatus.PENDING);
		verify(emailSender, after(1000).never()).send(any(), any(), any());

		refundService.settleFromWebhook("simulated", ProviderWebhook.refund(null, ProviderResult.approved("sim_r_wait")));
		refundService.settleFromWebhook("simulated", ProviderWebhook.refund(null, ProviderResult.approved("sim_r_wait")));

		verify(emailSender, timeout(5000)).send(any(), eq("Refund " + refund.refund().id() + " succeeded"), any());
		verify(emailSender, after(1000).times(1)).send(any(), any(), any());
		verify(smsSender, times(1)).send(any(), any());
	}

	@Test
	void aRolledBackRefundSendsNothingAndIsNotStored() {
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> {
			refundService.create(merchant, payment, new BigDecimal("10.00"), null, null);
			status.setRollbackOnly();
		});

		verify(emailSender, after(1000).never()).send(any(), any(), any());
		verify(smsSender, never()).send(any(), any());
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refunds WHERE transaction_id = ?", Integer.class,
				payment)).isZero();
	}

	@Test
	void aProviderFailureSendsNothing() {
		willThrow(new IllegalStateException("provider down")).given(simulatedProvider).refund(any());

		refundService.create(merchant, payment, new BigDecimal("10.00"), null, null);

		verify(emailSender, after(1000).never()).send(any(), any(), any());
		verify(smsSender, never()).send(any(), any());
	}

}
