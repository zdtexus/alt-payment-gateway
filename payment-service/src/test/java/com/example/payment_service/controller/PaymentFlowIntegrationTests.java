package com.example.payment_service.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentFlowIntegrationTests {

	private static final String DEMO_KEY = "demo-key-123";

	private static final String OTHER_KEY = "other-key-789";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@MockitoBean
	private PaymentProvider paymentProvider;

	@MockitoBean
	private EmailSender emailSender;

	@MockitoBean
	private SmsSender smsSender;

	private Merchant otherMerchant;

	@BeforeEach
	void createOtherMerchant() {
		Merchant merchant = new Merchant();
		merchant.setName("Other Shop");
		merchant.setApiKeyHash(ApiKeyHasher.hash(OTHER_KEY));
		merchant.setStatus(MerchantStatus.ACTIVE);
		otherMerchant = merchantRepository.save(merchant);
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());
	}

	@AfterEach
	void deleteData() {
		transactionRepository.deleteAll();
		merchantRepository.delete(otherMerchant);
	}

	@Test
	void approvedPaymentCanBeCreatedFetchedAndListed() throws Exception {
		MvcResult created = create(DEMO_KEY, "49.90", "EUR", "CARD").andReturn();
		String id = idOf(created);
		String location = created.getResponse().getHeader("Location");
		assertThat(location).isEqualTo("/api/v1/payments/" + id);

		mvc.perform(get(location).header("X-API-Key", DEMO_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.amount").value(49.90))
			.andExpect(jsonPath("$.currency").value("EUR"))
			.andExpect(jsonPath("$.paymentMethod").value("CARD"))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.declineReason").value(nullValue()))
			.andExpect(jsonPath("$.processedAt").isNotEmpty());

		mvc.perform(get("/api/v1/payments").header("X-API-Key", DEMO_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].id", contains(id)))
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void declinedPaymentIsStoredAndFilterableByStatus() throws Exception {
		given(paymentProvider.process(any())).willReturn(ProviderResult.declined("Insufficient funds"));

		MvcResult created = mvc
			.perform(post("/api/v1/payments").header("X-API-Key", DEMO_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("49.90", "EUR", "CARD")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DECLINED"))
			.andExpect(jsonPath("$.declineReason").value("Insufficient funds"))
			.andReturn();
		String id = idOf(created);

		mvc.perform(get("/api/v1/payments/" + id).header("X-API-Key", DEMO_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DECLINED"))
			.andExpect(jsonPath("$.declineReason").value("Insufficient funds"));
		mvc.perform(get("/api/v1/payments").param("status", "DECLINED").header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.content[*].id", contains(id)));
		mvc.perform(get("/api/v1/payments").param("status", "SUCCEEDED").header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.content", empty()))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void merchantCannotSeeAnotherMerchantsPayments() throws Exception {
		String demoPayment = idOf(create(DEMO_KEY, "49.90", "EUR", "CARD").andReturn());
		String otherPayment = idOf(create(OTHER_KEY, "15.00", "PLN", "KVIK").andReturn());

		mvc.perform(get("/api/v1/payments/" + demoPayment).header("X-API-Key", OTHER_KEY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("Payment " + demoPayment + " not found"));
		mvc.perform(get("/api/v1/payments/" + otherPayment).header("X-API-Key", DEMO_KEY))
			.andExpect(status().isNotFound());

		mvc.perform(get("/api/v1/payments").header("X-API-Key", OTHER_KEY))
			.andExpect(jsonPath("$.content[*].id", contains(otherPayment)))
			.andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(get("/api/v1/payments").header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.content[*].id", contains(demoPayment)))
			.andExpect(jsonPath("$.content[*].id", not(hasItem(otherPayment))))
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void listIsNewestFirstAndPaged() throws Exception {
		String first = idOf(create(DEMO_KEY, "1.00", "EUR", "CARD").andReturn());
		String second = idOf(create(DEMO_KEY, "2.00", "EUR", "CARD").andReturn());
		String third = idOf(create(DEMO_KEY, "3.00", "EUR", "CARD").andReturn());

		mvc.perform(get("/api/v1/payments").param("size", "2").header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.content[*].id", contains(third, second)))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.totalPages").value(2));
		mvc.perform(get("/api/v1/payments").param("size", "2").param("page", "1").header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.content[*].id", contains(first)));
	}

	@Test
	void ruleViolationStoresNothing() throws Exception {
		mvc.perform(post("/api/v1/payments").header("X-API-Key", DEMO_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("600.00", "EUR", "BONUS")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error").value("PAYMENT_RULE_VIOLATION"))
			.andExpect(jsonPath("$.message").value("Amount 600.00 exceeds BONUS limit 500.00"));

		mvc.perform(get("/api/v1/payments").header("X-API-Key", DEMO_KEY))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	private ResultActions create(String apiKey, String amount, String currency,
			String method) throws Exception {
		return mvc
			.perform(post("/api/v1/payments").header("X-API-Key", apiKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(amount, currency, method)))
			.andExpect(status().isCreated());
	}

	private static String idOf(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	private static String body(String amount, String currency, String method) {
		return "{\"amount\": %s, \"currency\": \"%s\", \"paymentMethod\": \"%s\"}".formatted(amount, currency, method);
	}

}
