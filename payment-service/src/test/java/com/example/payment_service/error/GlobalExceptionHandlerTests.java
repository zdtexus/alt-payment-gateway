package com.example.payment_service.error;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment_service.auth.ApiKeyAuthFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTests.TestController.class,
		excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ApiKeyAuthFilter.class))
@Import(GlobalExceptionHandlerTests.TestController.class)
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTests {

	private static final String SECRET_DETAIL = "secret detail: password=hunter2";

	private static final String DATABASE_DETAIL = "jdbc:postgresql://db:5432/payments user=payments";

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
	void invalidFieldAndHeaderValidatedTogetherAreNamedByFieldAndHeader() throws Exception {
		ResultActions result = mvc.perform(post("/test/payments-with-header").header("X-Ref", "toolong")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":-1,\"currency\":\"EUR\"}"));

		expectError(result, 400, "VALIDATION_FAILED", "X-Ref: size must be between 0 and 3; amount: must be greater than 0",
				"/test/payments-with-header");
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
	void unknownMerchantReturns404() throws Exception {
		ResultActions result = mvc.perform(get("/test/merchants/" + PAYMENT_ID));

		expectError(result, 404, "MERCHANT_NOT_FOUND", "Merchant " + PAYMENT_ID + " not found",
				"/test/merchants/" + PAYMENT_ID);
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

	@Test
	void unknownPathReturns404InCommonFormat() throws Exception {
		ResultActions result = mvc.perform(get("/api/v1/no-such-endpoint"));

		expectError(result, 404, "NOT_FOUND", "Not Found", "/api/v1/no-such-endpoint");
	}

	@Test
	void wrongMethodReturns405AndKeepsAllowHeader() throws Exception {
		ResultActions result = mvc.perform(delete("/test/payments"));

		expectError(result, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed", "/test/payments");
		result.andExpect(header().string("Allow", allOf(containsString("GET"), containsString("POST"))));
	}

	@Test
	void unsupportedContentTypeReturns415AndListsAcceptedTypes() throws Exception {
		ResultActions result = mvc
			.perform(post("/test/payments").contentType(MediaType.TEXT_PLAIN).content("amount=1"));

		expectError(result, 415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type", "/test/payments");
		result.andExpect(header().string("Accept", containsString("application/json")));
	}

	@Test
	void notAcceptableResponseTypeReturns406() throws Exception {
		ResultActions result = mvc.perform(get("/test/text").accept(MediaType.APPLICATION_JSON));

		expectError(result, 406, "NOT_ACCEPTABLE", "Not Acceptable", "/test/text");
	}

	@Test
	void unexpectedExceptionReturns500WithoutItsMessage(CapturedOutput output) throws Exception {
		ResultActions result = mvc.perform(get("/test/unexpected"));

		expectError(result, 500, "INTERNAL_SERVER_ERROR", "Internal Server Error", "/test/unexpected");
		result.andExpect(content().string(not(containsString(SECRET_DETAIL))))
			.andExpect(content().string(not(containsString("IllegalStateException"))));
		assertThat(output).contains("ERROR")
			.contains("Unhandled exception on GET /test/unexpected")
			.contains("java.lang.IllegalStateException");
	}

	@Test
	void clientErrorsAreNotLoggedAsErrors(CapturedOutput output) throws Exception {
		mvc.perform(get("/api/v1/no-such-endpoint"));
		mvc.perform(delete("/test/payments"));

		assertThat(output).doesNotContain("Unhandled exception").doesNotContain("Request failed");
	}

	@ParameterizedTest
	@ValueSource(strings = { "resource", "connection", "transaction", "rollback" })
	void databaseOutageReturns503WithRetryAfter(String kind, CapturedOutput output) throws Exception {
		ResultActions result = mvc.perform(get("/test/database/" + kind));

		expectError(result, 503, "SERVICE_UNAVAILABLE", "Database is unavailable", "/test/database/" + kind);
		result.andExpect(header().string("Retry-After", "5"))
			.andExpect(content().string(not(containsString("jdbc:"))))
			.andExpect(content().string(not(containsString("Exception"))));
		assertThat(output).contains("ERROR").contains("Database unavailable on GET /test/database/" + kind);
	}

	@Test
	void otherDatabaseErrorStays500() throws Exception {
		ResultActions result = mvc.perform(get("/test/database/constraint"));

		expectError(result, 500, "INTERNAL_SERVER_ERROR", "Internal Server Error", "/test/database/constraint");
		result.andExpect(header().doesNotExist("Retry-After"));
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

		@PostMapping("/test/payments-with-header")
		void createWithHeader(@Valid @RequestBody TestPaymentRequest request,
				@RequestHeader(name = "X-Ref", required = false) @Size(max = 3) String ref) {
		}

		@GetMapping("/test/payments")
		void list(@RequestParam @Positive int size) {
		}

		@GetMapping("/test/payments/{id}")
		void get(@PathVariable UUID id) {
			throw new PaymentNotFoundException(id);
		}

		@GetMapping("/test/merchants/{id}")
		void getMerchant(@PathVariable UUID id) {
			throw new MerchantNotFoundException(id);
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

		@GetMapping(path = "/test/text", produces = MediaType.TEXT_PLAIN_VALUE)
		String text() {
			return "text";
		}

		@GetMapping("/test/unexpected")
		void unexpected() {
			throw new IllegalStateException(SECRET_DETAIL);
		}

		@GetMapping("/test/database/{kind}")
		void databaseDown(@PathVariable String kind) {
			throw switch (kind) {
				case "resource" -> new DataAccessResourceFailureException("Connection refused " + DATABASE_DETAIL);
				case "connection" -> new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
						new SQLException("Connection refused " + DATABASE_DETAIL));
				case "rollback" -> new JpaSystemException(new RuntimeException("Unable to rollback against JDBC Connection",
						new SQLException("Connection is closed")));
				case "constraint" -> new DataIntegrityViolationException("duplicate key",
						new SQLException("duplicate key value violates unique constraint", "23505"));
				default -> new CannotCreateTransactionException("Could not open JPA EntityManager " + DATABASE_DETAIL);
			};
		}

		@GetMapping("/test/idempotency-key-reused")
		void idempotencyKeyReused() {
			throw new IdempotencyKeyReusedException();
		}

	}

}
