package com.example.payment_service.notification;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
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

	@MockitoBean
	private PaymentProvider paymentProvider;

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
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());
		AtomicReference<String> senderThread = new AtomicReference<>();
		willAnswer((invocation) -> {
			senderThread.set(Thread.currentThread().getName());
			return null;
		}).given(emailSender).send(any(), any(), any());

		PaymentResponse response = paymentService.process(merchantRepository.findByApiKey("demo-key-123").orElseThrow(),
				new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD));

		verify(emailSender, timeout(5000)).send(eq("shop@demo.local"), eq("Payment " + response.id() + " succeeded"),
				any());
		verify(smsSender, timeout(5000)).send("+380501112233", "Payment 49.90 EUR succeeded");
		assertThat(senderThread.get()).isNotEqualTo(Thread.currentThread().getName());
	}

}
