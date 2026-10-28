package com.example.payment_service.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(OutputCaptureExtension.class)
class NotificationListenerTests {

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	private final EmailSender emailSender = mock();

	private final SmsSender smsSender = mock();

	private final NotificationListener listener = new NotificationListener(emailSender, smsSender);

	@Test
	void succeededPaymentSendsReceiptByEmailAndSms() {
		listener.onPaymentProcessed(event(TransactionStatus.SUCCEEDED, null, "shop@example.com", "+380501234567"));

		verify(emailSender).send("shop@example.com", "Payment " + PAYMENT_ID + " succeeded", """
				Hello Demo Shop,

				Payment %s succeeded.

				Amount: 49.90 EUR
				Method: CARD
				Status: SUCCEEDED
				Processed at: 2026-01-15T10:30:01Z
				""".formatted(PAYMENT_ID));
		verify(smsSender).send("+380501234567", "Payment 49.90 EUR succeeded");
	}

	@Test
	void declinedPaymentIncludesReason() {
		listener.onPaymentProcessed(event(TransactionStatus.DECLINED, "Insufficient funds", "shop@example.com", null));

		verify(emailSender).send("shop@example.com", "Payment " + PAYMENT_ID + " declined", """
				Hello Demo Shop,

				Payment %s declined.

				Amount: 49.90 EUR
				Method: CARD
				Status: DECLINED
				Reason: Insufficient funds
				Processed at: 2026-01-15T10:30:01Z
				""".formatted(PAYMENT_ID));
	}

	@Test
	void missingEmailSkipsEmail() {
		listener.onPaymentProcessed(event(TransactionStatus.SUCCEEDED, null, null, "+380501234567"));

		verifyNoInteractions(emailSender);
		verify(smsSender).send(any(), any());
	}

	@Test
	void missingPhoneSkipsSms() {
		listener.onPaymentProcessed(event(TransactionStatus.SUCCEEDED, null, "shop@example.com", null));

		verify(emailSender).send(any(), any(), any());
		verify(smsSender, never()).send(any(), any());
	}

	@Test
	void emailFailureIsLoggedAndSmsIsStillSent(CapturedOutput output) {
		willThrow(new MailSendException("Connection refused")).given(emailSender).send(any(), any(), any());

		assertThatNoException().isThrownBy(() -> listener
			.onPaymentProcessed(event(TransactionStatus.SUCCEEDED, null, "shop@example.com", "+380501234567")));

		verify(smsSender).send("+380501234567", "Payment 49.90 EUR succeeded");
		assertThat(output).contains("Failed to send email notification for payment " + PAYMENT_ID)
			.contains("Connection refused");
	}

	@Test
	void smsFailureIsLogged(CapturedOutput output) {
		willThrow(new IllegalStateException("SMS gateway down")).given(smsSender).send(any(), any());

		assertThatNoException().isThrownBy(() -> listener
			.onPaymentProcessed(event(TransactionStatus.SUCCEEDED, null, "shop@example.com", "+380501234567")));

		verify(emailSender).send(any(), any(), any());
		assertThat(output).contains("Failed to send SMS notification for payment " + PAYMENT_ID)
			.contains("SMS gateway down");
	}

	private static PaymentProcessedEvent event(TransactionStatus status, String declineReason, String email,
			String phone) {
		return new PaymentProcessedEvent(PAYMENT_ID, "Demo Shop", email, phone, new BigDecimal("49.90"), "EUR",
				PaymentMethod.CARD, "simulated", status, declineReason, Instant.parse("2026-01-15T10:30:01Z"));
	}

}
