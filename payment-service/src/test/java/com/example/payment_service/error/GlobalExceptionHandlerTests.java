package com.example.payment_service.error;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment_service.auth.ApiKeyAuthFilter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTests.TestController.class,
		excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ApiKeyAuthFilter.class))
@Import(GlobalExceptionHandlerTests.TestController.class)
class GlobalExceptionHandlerTests {

	private static final UUID PAYMENT_ID = UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b");

	@Autowired
	private MockMvc mvc;

	@Test
	void invalidFieldReturns400() throws Exception {
		ResultActions result = mvc.perform(post("/test/payments").contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":-1,\"currency\":\"\"}"));

		expectError(result, 400, "VALIDATION_FAILED", "amount: must be greater than 0; currency: must not be blank",
				"/test/payments");
	}

	@Test
	void malformedJsonReturns400() throws Exception {
		ResultActions result = mvc
			.perform(post("/test/payments").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":"));

		expectError(result, 400, "VALIDATION_FAILED", "Malformed request body", "/test/payments");
	}

	@Test
	void invalidRequestParameterReturns400() throws Exception {
		ResultActions result = mvc.perform(get("/test/payments").param("size", "0"));

		expectError(result, 400, "VALIDATION_FAILED", "size: must be greater than 0", "/test/payments");
	}

	@Test
	void invalidPathVariableTypeReturns400() throws Exception {
		ResultActions result = mvc.perform(get("/test/payments/not-a-uuid"));

		expectError(result, 400, "VALIDATION_FAILED", "id: invalid value", "/test/payments/not-a-uuid");
	}

	@Test
	void invalidApiKeyReturns401() throws Exception {
		ResultActions result = mvc.perform(get("/test/invalid-api-key"));

		expectError(result, 401, "INVALID_API_KEY", "Missing or unknown API key", "/test/invalid-api-key");
	}

	@Test
	void suspendedMerchantReturns403() throws Exception {
		ResultActions result = mvc.perform(get("/test/merchant-suspended"));

		expectError(result, 403, "MERCHANT_SUSPENDED", "Merchant account is suspended", "/test/merchant-suspended");
	}

	@Test
	void unknownPaymentReturns404() throws Exception {
		ResultActions result = mvc.perform(get("/test/payments/" + PAYMENT_ID));

		expectError(result, 404, "PAYMENT_NOT_FOUND", "Payment " + PAYMENT_ID + " not found",
				"/test/payments/" + PAYMENT_ID);
	}

	@Test
	void paymentRuleViolationReturns422() throws Exception {
		ResultActions result = mvc.perform(get("/test/payment-rule-violation"));

		expectError(result, 422, "PAYMENT_RULE_VIOLATION", "Amount 600.00 exceeds BONUS limit 500.00",
				"/test/payment-rule-violation");
	}

	@Test
	void reusedIdempotencyKeyReturns422() throws Exception {
		ResultActions result = mvc.perform(get("/test/idempotency-key-reused"));

		expectError(result, 422, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used with a different request",
				"/test/idempotency-key-reused");
	}

	private static void expectError(ResultActions result, int status, String error, String message, String path)
			throws Exception {
		result.andExpect(status().is(status))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.timestamp").isNotEmpty())
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.error").value(error))
			.andExpect(jsonPath("$.message").value(message))
			.andExpect(jsonPath("$.path").value(path));
	}

	record TestPaymentRequest(@Positive BigDecimal amount, @NotBlank String currency) {
	}

	@RestController
	static class TestController {

		@PostMapping("/test/payments")
		void create(@Valid @RequestBody TestPaymentRequest request) {
		}

		@GetMapping("/test/payments")
		void list(@RequestParam @Positive int size) {
		}

		@GetMapping("/test/payments/{id}")
		void get(@PathVariable UUID id) {
			throw new PaymentNotFoundException(id);
		}

		@GetMapping("/test/invalid-api-key")
		void invalidApiKey() {
			throw new InvalidApiKeyException();
		}

		@GetMapping("/test/merchant-suspended")
		void merchantSuspended() {
			throw new MerchantSuspendedException();
		}

		@GetMapping("/test/payment-rule-violation")
		void paymentRuleViolation() {
			throw new PaymentRuleViolationException("Amount 600.00 exceeds BONUS limit 500.00");
		}

		@GetMapping("/test/idempotency-key-reused")
		void idempotencyKeyReused() {
			throw new IdempotencyKeyReusedException();
		}

	}

}
