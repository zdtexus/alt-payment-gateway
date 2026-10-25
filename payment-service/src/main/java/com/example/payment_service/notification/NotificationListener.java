package com.example.payment_service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;

@Component
public class NotificationListener {

	private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

	private final EmailSender emailSender;

	private final SmsSender smsSender;

	public NotificationListener(EmailSender emailSender, SmsSender smsSender) {
		this.emailSender = emailSender;
		this.smsSender = smsSender;
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPaymentProcessed(PaymentProcessedEvent event) {
		if (event.merchantEmail() != null) {
			sendSafely("email", event, () -> emailSender.send(event.merchantEmail(), subject(event), emailBody(event)));
		}
		if (event.merchantPhone() != null) {
			sendSafely("SMS", event, () -> smsSender.send(event.merchantPhone(), smsText(event)));
		}
	}

	private static void sendSafely(String channel, PaymentProcessedEvent event, Runnable send) {
		try {
			send.run();
		}
		catch (RuntimeException ex) {
			log.warn("Failed to send {} notification for payment {}", channel, event.paymentId(), ex);
		}
	}

	static String subject(PaymentProcessedEvent event) {
		return "Payment " + event.paymentId() + " " + outcome(event);
	}

	static String emailBody(PaymentProcessedEvent event) {
		StringBuilder body = new StringBuilder()
			.append("Hello ").append(event.merchantName()).append(",\n\n")
			.append("Payment ").append(event.paymentId()).append(' ').append(outcome(event)).append(".\n\n")
			.append("Amount: ").append(event.amount().toPlainString()).append(' ').append(event.currency()).append('\n')
			.append("Method: ").append(event.paymentMethod()).append('\n')
			.append("Status: ").append(event.status()).append('\n');
		if (event.declineReason() != null) {
			body.append("Reason: ").append(event.declineReason()).append('\n');
		}
		return body.append("Processed at: ").append(event.processedAt()).append('\n').toString();
	}

	static String smsText(PaymentProcessedEvent event) {
		return "Payment " + event.amount().toPlainString() + " " + event.currency() + " " + outcome(event);
	}

	private static String outcome(PaymentProcessedEvent event) {
		return (event.status() == TransactionStatus.SUCCEEDED) ? "succeeded" : "declined";
	}

}
