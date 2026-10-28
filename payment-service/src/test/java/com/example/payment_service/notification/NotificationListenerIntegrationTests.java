package com.example.payment_service.notification;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;
import com.example.payment_service.provider.SimulatedPaymentProvider;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationListenerIntegrationTests {

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private JavaMailSenderImpl mailSender;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoSpyBean
	private SimulatedPaymentProvider paymentProvider;

	@MockitoBean
	private EmailSender emailSender;

	@MockitoBean
	private SmsSender smsSender;

	@AfterEach
	void deleteTransactions() {
		transactionRepository.deleteAll();
	}

	@Test
	void committedPaymentSendsReceiptAsynchronously() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());
		AtomicReference<String> senderThread = new AtomicReference<>();
		willAnswer((invocation) -> {
			senderThread.set(Thread.currentThread().getName());
			return null;
		}).given(emailSender).send(any(), any(), any());

		PaymentResponse response = paymentService.process(demoMerchant(), new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD));

		verify(emailSender, timeout(5000)).send(eq("shop@demo.local"), eq("Payment " + response.id() + " succeeded"),
				any());
		verify(smsSender, timeout(5000)).send("+380501112233", "Payment 49.90 EUR succeeded");
		assertThat(senderThread.get()).isNotEqualTo(Thread.currentThread().getName());
	}

	@Test
	void rolledBackPaymentSendsNothing() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());
		AtomicReference<UUID> paymentId = new AtomicReference<>();

		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> {
			paymentId.set(paymentService.process(demoMerchant(),
					new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD))
				.id());
			status.setRollbackOnly();
		});

		verify(emailSender, after(1000).never()).send(any(), any(), any());
		verify(smsSender, never()).send(any(), any());
		assertThat(transactionRepository.findById(paymentId.get())).isEmpty();
	}

	@Test
	void pendingPaymentSendsNoReceiptYet() {
		willReturn(ProviderResult.pending("sim_ref-1")).given(paymentProvider).process(any());

		PaymentResponse response = paymentService.process(demoMerchant(),
				new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD));

		assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);
		verify(emailSender, after(1000).never()).send(any(), any(), any());
		verify(smsSender, never()).send(any(), any());
	}

	@Test
	void providerFailureSendsNothing() {
		willThrow(new IllegalStateException("Provider unreachable")).given(paymentProvider).process(any());

		PaymentResponse response = paymentService.process(demoMerchant(),
				new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD));

		assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);
		verify(emailSender, after(1000).never()).send(any(), any(), any());
		verify(smsSender, never()).send(any(), any());
	}

	@Test
	void failingMailServerDoesNotAffectPayment() {
		willReturn(ProviderResult.approved()).given(paymentProvider).process(any());
		willThrow(new MailSendException("Connection refused")).given(emailSender).send(any(), any(), any());

		PaymentResponse response = paymentService.process(demoMerchant(),
				new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD));

		assertThat(response.status()).isEqualTo(TransactionStatus.SUCCEEDED);
		verify(smsSender, timeout(5000)).send("+380501112233", "Payment 49.90 EUR succeeded");
		assertThat(transactionRepository.findById(response.id())).get()
			.extracting(Transaction::getStatus)
			.isEqualTo(TransactionStatus.SUCCEEDED);
	}

	@Test
	void mailServerCallsTimeOut() {
		assertThat(mailSender.getJavaMailProperties()).containsEntry("mail.smtp.connectiontimeout", "5000")
			.containsEntry("mail.smtp.timeout", "5000")
			.containsEntry("mail.smtp.writetimeout", "5000");
	}

	private Merchant demoMerchant() {
		return merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("demo-key-123")).orElseThrow();
	}

}
