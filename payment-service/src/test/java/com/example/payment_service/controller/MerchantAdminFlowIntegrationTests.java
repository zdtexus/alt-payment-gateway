package com.example.payment_service.controller;

import java.util.UUID;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MerchantAdminFlowIntegrationTests {

	private static final String ADMIN_KEY = "test-admin-key-0123456789";

	private static final String DEMO_KEY = "demo-key-123";

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

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

	@BeforeEach
	void approvePayments() {
		given(paymentProvider.process(any())).willReturn(ProviderResult.approved());
	}

	@AfterEach
	void deleteData() {
		transactionRepository.deleteAll();
		merchantRepository.findAll()
			.stream()
			.filter((merchant) -> !merchant.getId().equals(DEMO_MERCHANT_ID))
			.forEach(merchantRepository::delete);
	}

	@Test
	void merchantLifecycleFromCreationToRotationSuspensionAndReactivation() throws Exception {
		String created = admin(post("/admin/v1/merchants").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Acme Store\", \"email\": \"billing@acme.example\", \"phone\": \"+380501234567\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String merchantId = JsonPath.read(created, "$.id");
		String firstKey = JsonPath.read(created, "$.apiKey");

		String firstPayment = pay(firstKey).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String firstPaymentId = JsonPath.read(firstPayment, "$.id");
		then(emailSender).should(timeout(5000)).send(eq("billing@acme.example"), anyString(), anyString());
		then(smsSender).should(timeout(5000)).send(eq("+380501234567"), anyString());
		mvc.perform(get("/api/v1/payments/" + firstPaymentId).header("X-API-Key", DEMO_KEY))
			.andExpect(status().isNotFound());

		String rotated = admin(post("/admin/v1/merchants/" + merchantId + "/api-key")).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String secondKey = JsonPath.read(rotated, "$.apiKey");
		assertThat(secondKey).isNotEqualTo(firstKey);
		mvc.perform(get("/api/v1/payments/" + firstPaymentId).header("X-API-Key", firstKey))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("INVALID_API_KEY"));
		pay(firstKey).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/payments/" + firstPaymentId).header("X-API-Key", secondKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(firstPaymentId));

		updateStatus(merchantId, "SUSPENDED").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"));
		pay(secondKey).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("MERCHANT_SUSPENDED"));
		mvc.perform(get("/api/v1/payments").header("X-API-Key", secondKey))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("MERCHANT_SUSPENDED"));

		updateStatus(merchantId, "ACTIVE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		String secondPaymentId = JsonPath.read(pay(secondKey).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.id");

		mvc.perform(get("/api/v1/payments").header("X-API-Key", secondKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].id", containsInAnyOrder(firstPaymentId, secondPaymentId)))
			.andExpect(jsonPath("$.totalElements").value(2));
		then(paymentProvider).should(times(2)).process(any());
	}

	@ParameterizedTest
	@MethodSource("adminRequestsWithoutValidAdminKey")
	void adminEndpointsRejectMissingOrWrongAdminKeyAndChangeNothing(String endpoint, HttpMethod method, String body,
			String headerName, String headerValue) throws Exception {
		Merchant demo = merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow();
		long merchants = merchantRepository.count();

		MockHttpServletRequestBuilder request = request(method, endpoint);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		if (headerName != null) {
			request.header(headerName, headerValue);
		}

		mvc.perform(request)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("INVALID_ADMIN_KEY"))
			.andExpect(jsonPath("$.message").value("Missing or invalid admin key"));
		Merchant after = merchantRepository.findById(DEMO_MERCHANT_ID).orElseThrow();
		assertThat(merchantRepository.count()).isEqualTo(merchants);
		assertThat(after.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
		assertThat(after.getApiKeyHash()).isEqualTo(demo.getApiKeyHash());
	}

	static Stream<Arguments> adminRequestsWithoutValidAdminKey() {
		Stream.Builder<Arguments> requests = Stream.builder();
		Object[][] endpoints = { { "/admin/v1/merchants", HttpMethod.POST, "{\"name\": \"Intruder\"}" },
				{ "/admin/v1/merchants/" + DEMO_MERCHANT_ID, HttpMethod.PATCH, "{\"status\": \"SUSPENDED\"}" },
				{ "/admin/v1/merchants/" + DEMO_MERCHANT_ID + "/api-key", HttpMethod.POST, null } };
		String[][] credentials = { { null, null }, { "X-Admin-Key", "wrong-admin-key-0123456789" },
				{ "X-Admin-Key", "" }, { "X-API-Key", DEMO_KEY } };
		for (Object[] endpoint : endpoints) {
			for (String[] credential : credentials) {
				requests.add(Arguments.of(endpoint[0], endpoint[1], endpoint[2], credential[0], credential[1]));
			}
		}
		return requests.build();
	}

	private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header("X-Admin-Key", ADMIN_KEY));
	}

	private ResultActions updateStatus(String merchantId, String status) throws Exception {
		return admin(patch("/admin/v1/merchants/" + merchantId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\": \"" + status + "\"}"));
	}

	private ResultActions pay(String apiKey) throws Exception {
		return mvc.perform(post("/api/v1/payments").header("X-API-Key", apiKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": 49.90, \"currency\": \"EUR\", \"paymentMethod\": \"CARD\"}"));
	}

}
