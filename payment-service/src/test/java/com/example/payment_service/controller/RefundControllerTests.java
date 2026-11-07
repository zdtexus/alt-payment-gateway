package com.example.payment_service.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.dto.RefundResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.entity.RefundStatus;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.IdempotencyKeyReusedException;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.error.PaymentNotRefundableException;
import com.example.payment_service.error.RefundExceedsRemainingException;
import com.example.payment_service.error.RefundNotFoundException;
import com.example.payment_service.error.RefundNotSupportedException;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.service.RefundOutcome;
import com.example.payment_service.service.RefundService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RefundController.class)
class RefundControllerTests {

	private static final String API_KEY = "test-key";

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	private static final UUID REFUND_ID = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

	private static final String REFUNDS = "/api/v1/payments/" + PAYMENT_ID + "/refunds";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private RefundService refundService;

	@MockitoBean
	private MerchantRepository merchantRepository;

	private final Merchant merchant = new Merchant();

	@BeforeEach
	void authenticateMerchant() {
		merchant.setId(UUID.randomUUID());
		merchant.setName("Test Shop");
		merchant.setStatus(MerchantStatus.ACTIVE);
		given(merchantRepository.findByApiKeyHash(ApiKeyHasher.hash(API_KEY))).willReturn(Optional.of(merchant));
	}

	@Test
	void createReturns201WithLocationAndTheRefund() throws Exception {
		given(refundService.create(same(merchant), eq(PAYMENT_ID), eq(new BigDecimal("20.00")), eq("Item returned"),
				isNull()))
			.willReturn(new RefundOutcome(refund(RefundStatus.SUCCEEDED), false));

		mvc.perform(post(REFUNDS).header("X-API-Key", API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 20.00, \"reason\": \"Item returned\"}"))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", REFUNDS + "/" + REFUND_ID))
			.andExpect(header().doesNotExist("Idempotent-Replayed"))
			.andExpect(jsonPath("$.id").value(REFUND_ID.toString()))
			.andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
			.andExpect(jsonPath("$.amount").value(20.00))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"));
	}

	@Test
	void withoutABodyEverythingRemainingIsRefunded() throws Exception {
		given(refundService.create(same(merchant), eq(PAYMENT_ID), isNull(), isNull(), isNull()))
			.willReturn(new RefundOutcome(refund(RefundStatus.SUCCEEDED), false));

		mvc.perform(post(REFUNDS).header("X-API-Key", API_KEY)).andExpect(status().isCreated());
		mvc.perform(post(REFUNDS).header("X-API-Key", API_KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isCreated());

		verify(refundService, org.mockito.Mockito.times(2)).create(same(merchant), eq(PAYMENT_ID), isNull(), isNull(),
				isNull());
	}

	@Test
	void replayIsMarkedWithTheIdempotentReplayedHeader() throws Exception {
		given(refundService.create(same(merchant), eq(PAYMENT_ID), any(), any(), eq("return-1")))
			.willReturn(new RefundOutcome(refund(RefundStatus.SUCCEEDED), true));

		mvc.perform(post(REFUNDS).header("X-API-Key", API_KEY).header("Idempotency-Key", "return-1"))
			.andExpect(status().isCreated())
			.andExpect(header().string("Idempotent-Replayed", "true"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"amount\": 0}", "{\"amount\": -1.00}", "{\"amount\": 1.005}", "{\"amount\": \"x\"}" })
	void invalidAmountIsRejected(String body) throws Exception {
		mvc.perform(post(REFUNDS).header("X-API-Key", API_KEY).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
		verifyNoInteractions(refundService);
	}

	@Test
	void tooLongReasonOrKeyIsRejected() throws Exception {
		mvc.perform(post(REFUNDS).header("X-API-Key", API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\": \"" + "r".repeat(256) + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("reason: size must be between 0 and 255"));
		mvc.perform(post(REFUNDS).header("X-API-Key", API_KEY).header("Idempotency-Key", "k".repeat(256)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Idempotency-Key: size must be between 1 and 255"));
		verifyNoInteractions(refundService);
	}

	@Test
	void invalidPaymentIdIsRejected() throws Exception {
		mvc.perform(post("/api/v1/payments/not-a-uuid/refunds").header("X-API-Key", API_KEY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("paymentId: invalid value"));
	}

	@Test
	void serviceErrorsUseTheCommonFormat() throws Exception {
		given(refundService.create(any(), eq(PAYMENT_ID), eq(new BigDecimal("1.00")), any(), any()))
			.willThrow(new PaymentNotFoundException(PAYMENT_ID));
		given(refundService.create(any(), eq(PAYMENT_ID), eq(new BigDecimal("2.00")), any(), any()))
			.willThrow(new PaymentNotRefundableException(PAYMENT_ID, TransactionStatus.DECLINED));
		given(refundService.create(any(), eq(PAYMENT_ID), eq(new BigDecimal("3.00")), any(), any()))
			.willThrow(new RefundExceedsRemainingException(new BigDecimal("3.00"), new BigDecimal("2.00")));
		given(refundService.create(any(), eq(PAYMENT_ID), eq(new BigDecimal("4.00")), any(), any()))
			.willThrow(new RefundNotSupportedException());
		given(refundService.create(any(), eq(PAYMENT_ID), eq(new BigDecimal("5.00")), any(), any()))
			.willThrow(new IdempotencyKeyReusedException());

		expectError("1.00", 404, "PAYMENT_NOT_FOUND");
		expectError("2.00", 409, "PAYMENT_NOT_REFUNDABLE");
		expectError("3.00", 422, "REFUND_EXCEEDS_REMAINING");
		expectError("4.00", 422, "REFUND_NOT_SUPPORTED");
		expectError("5.00", 422, "IDEMPOTENCY_KEY_REUSED");
	}

	@Test
	void listReturnsTheRefundsAsAnArray() throws Exception {
		given(refundService.list(same(merchant), eq(PAYMENT_ID)))
			.willReturn(List.of(refund(RefundStatus.SUCCEEDED), refund(RefundStatus.PENDING)));

		mvc.perform(get(REFUNDS).header("X-API-Key", API_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[1].status").value("PENDING"));
	}

	@Test
	void getReturnsOneRefundOrNotFound() throws Exception {
		UUID unknown = UUID.randomUUID();
		given(refundService.get(same(merchant), eq(PAYMENT_ID), eq(REFUND_ID))).willReturn(refund(RefundStatus.DECLINED));
		given(refundService.get(same(merchant), eq(PAYMENT_ID), eq(unknown))).willThrow(new RefundNotFoundException(unknown));

		mvc.perform(get(REFUNDS + "/" + REFUND_ID).header("X-API-Key", API_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.declineReason").value("Card account closed"));
		mvc.perform(get(REFUNDS + "/" + unknown).header("X-API-Key", API_KEY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("REFUND_NOT_FOUND"))
			.andExpect(jsonPath("$.path").value(REFUNDS + "/" + unknown));
	}

	@Test
	void refundsNeedAnApiKey() throws Exception {
		mvc.perform(post(REFUNDS)).andExpect(status().isUnauthorized());
		mvc.perform(get(REFUNDS)).andExpect(status().isUnauthorized());
		verifyNoInteractions(refundService);
	}

	private void expectError(String amount, int status, String error) throws Exception {
		mvc.perform(post(REFUNDS).header("X-API-Key", API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": " + amount + "}"))
			.andExpect(status().is(status))
			.andExpect(jsonPath("$.error").value(error))
			.andExpect(jsonPath("$.path").value(REFUNDS));
	}

	private static RefundResponse refund(RefundStatus status) {
		return new RefundResponse(REFUND_ID, PAYMENT_ID, new BigDecimal("20.00"), "EUR", status, "Item returned",
				(status == RefundStatus.DECLINED) ? "Card account closed" : null, Instant.parse("2026-01-16T09:00:00Z"),
				(status == RefundStatus.PENDING) ? null : Instant.parse("2026-01-16T09:00:01Z"));
	}

}
