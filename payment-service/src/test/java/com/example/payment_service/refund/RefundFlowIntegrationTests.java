package com.example.payment_service.refund;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = { "PAYMENT_PROVIDER_SUCCESS_RATE=1.0", "PAYMENT_PROVIDER_LATENCY=0ms" })
class RefundFlowIntegrationTests {

	private static final String DEMO_KEY = "demo-key-123";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private String payment;

	@BeforeEach
	void fiftyEuroCardPayment() throws Exception {
		MvcResult result = mvc.perform(post("/api/v1/payments").header("X-API-Key", DEMO_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 50.00, \"currency\": \"EUR\", \"paymentMethod\": \"CARD\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.refundedAmount").value(0.00))
			.andReturn();
		payment = "/api/v1/payments/" + JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	@Test
	void aPaymentIsRefundedInTwoPartsAndThenNoFurther() throws Exception {
		MvcResult first = mvc.perform(post(payment + "/refunds").header("X-API-Key", DEMO_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 20.00, \"reason\": \"Item returned\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.amount").value(20.00))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.reason").value("Item returned"))
			.andReturn();
		String location = first.getResponse().getHeader("Location");
		mvc.perform(get(payment).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.refundedAmount").value(20.00));

		mvc.perform(post(payment + "/refunds").header("X-API-Key", DEMO_KEY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.amount").value(30.00));
		mvc.perform(post(payment + "/refunds").header("X-API-Key", DEMO_KEY))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error").value("REFUND_EXCEEDS_REMAINING"))
			.andExpect(jsonPath("$.message").value("Payment is already fully refunded"));

		mvc.perform(get(payment).header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.refundedAmount").value(50.00));
		mvc.perform(get(payment + "/refunds").header("X-API-Key", DEMO_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].amount").value(20.00))
			.andExpect(jsonPath("$[1].amount").value(30.00));
		mvc.perform(get(location).header("X-API-Key", DEMO_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(JsonPath.<String>read(first.getResponse().getContentAsString(), "$.id")));
		mvc.perform(get("/api/v1/payments?size=100").header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.content[?(@.id == '" + payment.substring(payment.lastIndexOf('/') + 1)
					+ "')].refundedAmount").value(hasItem(50.00)));
	}

	@Test
	void anOverRefundIsRejectedAndNothingIsStored() throws Exception {
		mvc.perform(post(payment + "/refunds").header("X-API-Key", DEMO_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 50.01}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.message").value("Refund 50.01 exceeds the remaining refundable amount 50.00"));

		mvc.perform(get(payment + "/refunds").header("X-API-Key", DEMO_KEY)).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void aRetryWithTheSameKeyReturnsTheSameRefund() throws Exception {
		String key = "return-" + UUID.randomUUID();
		MvcResult first = mvc.perform(post(payment + "/refunds").header("X-API-Key", DEMO_KEY)
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 10.00}"))
			.andExpect(status().isCreated())
			.andExpect(header().doesNotExist("Idempotent-Replayed"))
			.andReturn();

		mvc.perform(post(payment + "/refunds").header("X-API-Key", DEMO_KEY)
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 10}"))
			.andExpect(status().isCreated())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(header().string("Location", first.getResponse().getHeader("Location")));
		mvc.perform(get(payment).header("X-API-Key", DEMO_KEY)).andExpect(jsonPath("$.refundedAmount").value(10.00));
	}

	@Test
	void anotherMerchantCannotSeeOrRefundThePayment() throws Exception {
		String otherKey = "other-key-" + UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO merchants (id, name, api_key_hash, status) VALUES (?, 'Other Shop', ?, 'ACTIVE')",
				UUID.randomUUID(), ApiKeyHasher.hash(otherKey));
		mvc.perform(post(payment + "/refunds").header("X-API-Key", DEMO_KEY)).andExpect(status().isCreated());
		String refundId = JsonPath.read(mvc.perform(get(payment + "/refunds").header("X-API-Key", DEMO_KEY))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$[0].id");

		mvc.perform(post(payment + "/refunds").header("X-API-Key", otherKey))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
		mvc.perform(get(payment + "/refunds").header("X-API-Key", otherKey)).andExpect(status().isNotFound());
		mvc.perform(get(payment + "/refunds/" + refundId).header("X-API-Key", otherKey))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
	}

	@Test
	void aRefundIsOnlyFoundUnderItsOwnPayment() throws Exception {
		MvcResult other = mvc.perform(post("/api/v1/payments").header("X-API-Key", DEMO_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 10.00, \"currency\": \"EUR\", \"paymentMethod\": \"CARD\"}"))
			.andReturn();
		String otherPayment = "/api/v1/payments/" + JsonPath.read(other.getResponse().getContentAsString(), "$.id");
		String otherRefund = JsonPath.read(mvc.perform(post(otherPayment + "/refunds").header("X-API-Key", DEMO_KEY))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.id");

		mvc.perform(get(payment + "/refunds/" + otherRefund).header("X-API-Key", DEMO_KEY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("REFUND_NOT_FOUND"));
		mvc.perform(get(payment + "/refunds").header("X-API-Key", DEMO_KEY)).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void anUnknownRefundIsNotFound() throws Exception {
		UUID unknown = UUID.randomUUID();

		mvc.perform(get(payment + "/refunds/" + unknown).header("X-API-Key", DEMO_KEY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("REFUND_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("Refund " + unknown + " not found"));
	}

}
