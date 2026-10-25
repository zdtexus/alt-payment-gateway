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

import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PageResponse;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.error.PaymentNotFoundException;
import com.example.payment_service.error.PaymentRuleViolationException;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.service.PaymentService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTests {

	private static final String API_KEY = "test-key";

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	private static final String VALID_BODY = "{\"amount\": 49.90, \"currency\": \"EUR\", \"paymentMethod\": \"CARD\"}";

	private static final CreatePaymentRequest VALID_REQUEST = new CreatePaymentRequest(new BigDecimal("49.90"), "EUR",
			PaymentMethod.CARD);

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private PaymentService paymentService;

	@MockitoBean
	private MerchantRepository merchantRepository;

	private final Merchant merchant = new Merchant();

	@BeforeEach
	void authenticateMerchant() {
		merchant.setId(UUID.randomUUID());
		merchant.setName("Test Shop");
		merchant.setStatus(MerchantStatus.ACTIVE);
		given(merchantRepository.findByApiKey(API_KEY)).willReturn(Optional.of(merchant));
	}

	@Test
	void createReturns201WithLocation() throws Exception {
		given(paymentService.process(same(merchant), eq(VALID_REQUEST)))
			.willReturn(payment(TransactionStatus.SUCCEEDED, null));

		mvc.perform(post("/api/v1/payments").header("X-API-Key", API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/v1/payments/" + PAYMENT_ID))
			.andExpect(jsonPath("$.id").value(PAYMENT_ID.toString()))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"));
	}

	@Test
	void declinedPaymentIsStillCreated() throws Exception {
		given(paymentService.process(same(merchant), eq(VALID_REQUEST)))
			.willReturn(payment(TransactionStatus.DECLINED, "Insufficient funds"));

		mvc.perform(post("/api/v1/payments").header("X-API-Key", API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DECLINED"))
			.andExpect(jsonPath("$.declineReason").value("Insufficient funds"));
	}

	@Test
	void invalidBodyReturns400WithoutProcessing() throws Exception {
		mvc.perform(post("/api/v1/payments").header("X-API-Key", API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": -5, \"currency\": \"eur\", \"paymentMethod\": \"CARD\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message")
				.value("amount: must be greater than 0; currency: must be a 3-letter ISO currency code"));
		verifyNoInteractions(paymentService);
	}

	@Test
	void ruleViolationReturns422() throws Exception {
		given(paymentService.process(any(), any()))
			.willThrow(new PaymentRuleViolationException("Amount 600.00 exceeds BONUS limit 500.00"));

		mvc.perform(post("/api/v1/payments").header("X-API-Key", API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 600.00, \"currency\": \"EUR\", \"paymentMethod\": \"BONUS\"}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error").value("PAYMENT_RULE_VIOLATION"))
			.andExpect(jsonPath("$.message").value("Amount 600.00 exceeds BONUS limit 500.00"));
	}

	@Test
	void getReturnsPayment() throws Exception {
		given(paymentService.get(same(merchant), eq(PAYMENT_ID))).willReturn(payment(TransactionStatus.SUCCEEDED, null));

		mvc.perform(get("/api/v1/payments/" + PAYMENT_ID).header("X-API-Key", API_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(PAYMENT_ID.toString()));
	}

	@Test
	void getUnknownPaymentReturns404() throws Exception {
		given(paymentService.get(same(merchant), eq(PAYMENT_ID))).willThrow(new PaymentNotFoundException(PAYMENT_ID));

		mvc.perform(get("/api/v1/payments/" + PAYMENT_ID).header("X-API-Key", API_KEY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
	}

	@Test
	void listUsesDefaults() throws Exception {
		given(paymentService.list(same(merchant), eq(null), eq(0), eq(20)))
			.willReturn(new PageResponse<>(List.of(payment(TransactionStatus.SUCCEEDED, null)), 0, 20, 1, 1));

		mvc.perform(get("/api/v1/payments").header("X-API-Key", API_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].id").value(PAYMENT_ID.toString()))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void listPassesFilterAndPaging() throws Exception {
		given(paymentService.list(same(merchant), eq(TransactionStatus.DECLINED), eq(2), eq(5)))
			.willReturn(new PageResponse<>(List.of(), 2, 5, 0, 0));

		mvc.perform(get("/api/v1/payments").header("X-API-Key", API_KEY)
			.param("status", "DECLINED")
			.param("page", "2")
			.param("size", "5")).andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2));

		verify(paymentService).list(same(merchant), eq(TransactionStatus.DECLINED), eq(2), eq(5));
	}

	@ParameterizedTest
	@ValueSource(strings = { "page=-1", "size=0", "size=101", "status=FOO" })
	void listRejectsInvalidParameters(String query) throws Exception {
		mvc.perform(get("/api/v1/payments?" + query).header("X-API-Key", API_KEY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
		verifyNoInteractions(paymentService);
	}

	private static PaymentResponse payment(TransactionStatus status, String declineReason) {
		return new PaymentResponse(PAYMENT_ID, new BigDecimal("49.90"), "EUR", PaymentMethod.CARD, status,
				declineReason, Instant.parse("2026-01-15T10:30:00Z"), Instant.parse("2026-01-15T10:30:01Z"));
	}

}
