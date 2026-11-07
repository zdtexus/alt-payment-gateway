package com.example.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.example.payment_service.entity.CaptureMethod;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class PaymentDtoJsonTests {

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	@Autowired
	private JacksonTester<CreatePaymentRequest> requestJson;

	@Autowired
	private JacksonTester<PaymentResponse> responseJson;

	@Test
	void readsReadmeRequestExample() throws Exception {
		CreatePaymentRequest request = requestJson
			.parseObject("{\"amount\": 49.90, \"currency\": \"EUR\", \"paymentMethod\": \"CARD\"}");

		assertThat(request.amount()).isEqualByComparingTo("49.90");
		assertThat(request.currency()).isEqualTo("EUR");
		assertThat(request.paymentMethod()).isEqualTo(PaymentMethod.CARD);
		assertThat(request.captureMethod()).isEqualTo(CaptureMethod.AUTOMATIC);
	}

	@Test
	void readsTheCaptureMethod() throws Exception {
		assertThat(requestJson.parseObject(
				"{\"amount\": 49.90, \"currency\": \"EUR\", \"paymentMethod\": \"CARD\", \"captureMethod\": \"MANUAL\"}")
			.captureMethod()).isEqualTo(CaptureMethod.MANUAL);
		assertThat(requestJson.parseObject(
				"{\"amount\": 49.90, \"currency\": \"EUR\", \"paymentMethod\": \"CARD\", \"captureMethod\": null}")
			.captureMethod()).isEqualTo(CaptureMethod.AUTOMATIC);
	}

	@Test
	void writesAnAuthorizedPaymentWithItsExpiry() throws Exception {
		Transaction transaction = declinedTransaction();
		transaction.setStatus(TransactionStatus.AUTHORIZED);
		transaction.setDeclineReason(null);
		transaction.setProcessedAt(null);
		transaction.setCaptureMethod(CaptureMethod.MANUAL);
		transaction.setAuthorizedAmount(new BigDecimal("600.00"));
		transaction.setAuthorizationExpiresAt(Instant.parse("2026-01-22T10:30:00Z"));

		assertThat(responseJson.write(PaymentResponse.from(transaction, PaymentResponse.NOTHING_REFUNDED)))
			.isStrictlyEqualToJson("""
					{
					  "id": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b",
					  "amount": 600.00,
					  "refundedAmount": 0.00,
					  "currency": "EUR",
					  "paymentMethod": "CARD",
					  "captureMethod": "MANUAL",
					  "status": "AUTHORIZED",
					  "declineReason": null,
					  "createdAt": "2026-01-15T10:30:00Z",
					  "processedAt": null,
					  "authorizationExpiresAt": "2026-01-22T10:30:00Z"
					}
					""");
	}

	@Test
	void writesPaymentResponseWithoutProviderDetails() throws Exception {
		PaymentResponse response = PaymentResponse.from(declinedTransaction(), new BigDecimal("0.00"));

		assertThat(responseJson.write(response)).isStrictlyEqualToJson("""
				{
				  "id": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b",
				  "amount": 600.00,
				  "refundedAmount": 0.00,
				  "currency": "EUR",
				  "paymentMethod": "CARD",
				  "captureMethod": "AUTOMATIC",
				  "status": "DECLINED",
				  "declineReason": "Insufficient funds",
				  "createdAt": "2026-01-15T10:30:00Z",
				  "processedAt": "2026-01-15T10:30:01Z",
				  "authorizationExpiresAt": null
				}
				""");
	}

	@Test
	void refundedPaymentKeepsItsStatusAndShowsTheRefundedSum() throws Exception {
		Transaction transaction = declinedTransaction();
		transaction.setStatus(TransactionStatus.SUCCEEDED);
		transaction.setDeclineReason(null);

		assertThat(responseJson.write(PaymentResponse.from(transaction, new BigDecimal("20.00"))))
			.extractingJsonPathStringValue("$.status")
			.isEqualTo("SUCCEEDED");
		assertThat(responseJson.write(PaymentResponse.from(transaction, new BigDecimal("20.00"))))
			.extractingJsonPathNumberValue("$.refundedAmount")
			.isEqualTo(20.00);
	}

	@Test
	void pageResponseMapsContentAndPaging() {
		PageImpl<Transaction> page = new PageImpl<>(List.of(declinedTransaction()), PageRequest.of(1, 1), 3);

		PageResponse<PaymentResponse> response = PageResponse.from(page,
				(transaction) -> PaymentResponse.from(transaction, PaymentResponse.NOTHING_REFUNDED));

		assertThat(response.content()).extracting(PaymentResponse::id).containsExactly(PAYMENT_ID);
		assertThat(response.page()).isEqualTo(1);
		assertThat(response.size()).isEqualTo(1);
		assertThat(response.totalElements()).isEqualTo(3);
		assertThat(response.totalPages()).isEqualTo(3);
	}

	private static Transaction declinedTransaction() {
		Transaction transaction = new Transaction();
		transaction.setId(PAYMENT_ID);
		transaction.setAmount(new BigDecimal("600.00"));
		transaction.setCurrency("EUR");
		transaction.setPaymentMethod(PaymentMethod.CARD);
		transaction.setStatus(TransactionStatus.DECLINED);
		transaction.setDeclineReason("Insufficient funds");
		transaction.setProvider("simulated");
		transaction.setProviderReference("sim_ref-1");
		transaction.setCreatedAt(Instant.parse("2026-01-15T10:30:00Z"));
		transaction.setProcessedAt(Instant.parse("2026-01-15T10:30:01Z"));
		return transaction;
	}

}
