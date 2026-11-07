package com.example.payment_service.event;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProcessedEventTests {

	@Test
	void aCapturedPaymentReportsTheCapturedAmount() {
		Transaction payment = payment();
		payment.setCapturedAmount(new BigDecimal("30.00"));

		assertThat(PaymentProcessedEvent.from(payment).amount()).isEqualByComparingTo("30.00");
	}

	@Test
	void otherPaymentsReportTheirAmount() {
		assertThat(PaymentProcessedEvent.from(payment()).amount()).isEqualByComparingTo("49.90");
	}

	private static Transaction payment() {
		Merchant merchant = new Merchant();
		merchant.setName("Demo Shop");
		Transaction payment = new Transaction();
		payment.setId(UUID.randomUUID());
		payment.setMerchant(merchant);
		payment.setAmount(new BigDecimal("49.90"));
		payment.setCurrency("EUR");
		payment.setPaymentMethod(PaymentMethod.CARD);
		payment.setStatus(TransactionStatus.SUCCEEDED);
		return payment;
	}

}
