package com.example.payment_service.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

	private static PaymentProcessedEvent event(TransactionStatus status, String declineReason, String email,
			String phone) {
		return new PaymentProcessedEvent(PAYMENT_ID, "Demo Shop", email, phone, new BigDecimal("49.90"), "EUR",
				PaymentMethod.CARD, status, declineReason, Instant.parse("2026-01-15T10:30:01Z"));
	}

}
