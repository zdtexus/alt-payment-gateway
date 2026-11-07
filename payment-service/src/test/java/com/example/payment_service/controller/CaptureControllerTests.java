package com.example.payment_service.controller;

import java.math.BigDecimal;
import java.time.Instant;
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
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.CaptureMethod;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.PendingAction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.PaymentNotCapturableException;
import com.example.payment_service.error.VoidDeclinedException;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.service.CaptureService;
import com.example.payment_service.service.PaymentOutcome;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CaptureController.class)
class CaptureControllerTests {

	private static final String API_KEY = "test-key";

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private CaptureService captureService;

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
	void captureOfAnAmountReturns200WithTheCapturedPayment() throws Exception {
		given(captureService.capture(same(merchant), eq(PAYMENT_ID),
				argThat((amount) -> amount != null && amount.compareTo(new BigDecimal("30")) == 0), eq("ship-1")))
			.willReturn(new PaymentOutcome(payment(TransactionStatus.SUCCEEDED, "30.00", null), false));

		mvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID).header("X-API-Key", API_KEY)
			.header("Idempotency-Key", "ship-1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 30.00}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.amount").value(49.90))
			.andExpect(jsonPath("$.capturedAmount").value(30.00))
			.andExpect(jsonPath("$.pendingAction").isEmpty())
			.andExpect(header().doesNotExist("Idempotent-Replayed"));
	}

	@Test
	void captureWithoutABodyCapturesTheWholeAuthorization() throws Exception {
		given(captureService.capture(same(merchant), eq(PAYMENT_ID), isNull(), isNull()))
			.willReturn(new PaymentOutcome(payment(TransactionStatus.SUCCEEDED, "49.90", null), false));

		mvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID).header("X-API-Key", API_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.capturedAmount").value(49.90));
	}

	@Test
	void aCaptureWithAnUnknownResultReturns202() throws Exception {
		given(captureService.capture(same(merchant), eq(PAYMENT_ID), isNull(), isNull()))
			.willReturn(new PaymentOutcome(payment(TransactionStatus.AUTHORIZED, null, PendingAction.CAPTURE), false));

		mvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID).header("X-API-Key", API_KEY))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("AUTHORIZED"))
			.andExpect(jsonPath("$.pendingAction").value("CAPTURE"));
	}

	@Test
	void aReplayedCaptureCarriesTheHeader() throws Exception {
		given(captureService.capture(same(merchant), eq(PAYMENT_ID), isNull(), eq("ship-1")))
			.willReturn(new PaymentOutcome(payment(TransactionStatus.SUCCEEDED, "49.90", null), true));

		mvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID).header("X-API-Key", API_KEY)
			.header("Idempotency-Key", "ship-1"))
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1.00", "30.001" })
	void anInvalidCaptureAmountReturns400WithoutCapturing(String amount) throws Exception {
		mvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID).header("X-API-Key", API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": " + amount + "}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
		verifyNoInteractions(captureService);
	}

	@Test
	void aKeyLongerThan255CharactersReturns400() throws Exception {
		mvc.perform(post("/api/v1/payments/{id}/void", PAYMENT_ID).header("X-API-Key", API_KEY)
			.header("Idempotency-Key", "k".repeat(256)))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(captureService);
	}

	@Test
	void voidReturns200WithTheVoidedPayment() throws Exception {
		given(captureService.voidAuthorization(same(merchant), eq(PAYMENT_ID), eq("cancel-1")))
			.willReturn(new PaymentOutcome(payment(TransactionStatus.VOIDED, null, null), false));

		mvc.perform(post("/api/v1/payments/{id}/void", PAYMENT_ID).header("X-API-Key", API_KEY)
			.header("Idempotency-Key", "cancel-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("VOIDED"))
			.andExpect(jsonPath("$.capturedAmount").isEmpty());
	}

	@Test
	void aVoidWithAnUnknownResultReturns202() throws Exception {
		given(captureService.voidAuthorization(same(merchant), eq(PAYMENT_ID), isNull()))
			.willReturn(new PaymentOutcome(payment(TransactionStatus.AUTHORIZED, null, PendingAction.VOID), false));

		mvc.perform(post("/api/v1/payments/{id}/void", PAYMENT_ID).header("X-API-Key", API_KEY))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.pendingAction").value("VOID"));
	}

	@Test
	void aRefusedCaptureReturns409() throws Exception {
		given(captureService.capture(any(), any(), any(), any())).willThrow(new PaymentNotCapturableException(
				"Payment " + PAYMENT_ID + " is VOIDED; only AUTHORIZED payments can be captured"));

		mvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID).header("X-API-Key", API_KEY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("PAYMENT_NOT_CAPTURABLE"))
			.andExpect(jsonPath("$.path").value("/api/v1/payments/" + PAYMENT_ID + "/capture"));
	}

	@Test
	void aDeclinedVoidReturns422() throws Exception {
		given(captureService.voidAuthorization(any(), any(), any()))
			.willThrow(new VoidDeclinedException("Already captured"));

		mvc.perform(post("/api/v1/payments/{id}/void", PAYMENT_ID).header("X-API-Key", API_KEY))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error").value("VOID_DECLINED"));
	}

	@Test
	void withoutAnApiKeyNothingIsCapturedOrVoided() throws Exception {
		mvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/payments/{id}/void", PAYMENT_ID)).andExpect(status().isUnauthorized());
		verifyNoInteractions(captureService);
	}

	@Test
	void anInvalidPaymentIdReturns400() throws Exception {
		mvc.perform(post("/api/v1/payments/abc/capture").header("X-API-Key", API_KEY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
		verifyNoInteractions(captureService);
	}

	private static PaymentResponse payment(TransactionStatus status, String captured, PendingAction pending) {
		boolean done = status == TransactionStatus.SUCCEEDED || status == TransactionStatus.VOIDED;
		return new PaymentResponse(PAYMENT_ID, new BigDecimal("49.90"), new BigDecimal("0.00"), "EUR",
				PaymentMethod.CARD, CaptureMethod.MANUAL, status, null, Instant.parse("2026-01-15T10:30:00Z"),
				done ? Instant.parse("2026-01-16T09:00:00Z") : null, Instant.parse("2026-01-22T10:30:00Z"),
				(captured != null) ? new BigDecimal(captured) : null, pending);
	}

}
