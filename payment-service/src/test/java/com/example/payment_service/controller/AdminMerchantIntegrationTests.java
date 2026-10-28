package com.example.payment_service.controller;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.repository.MerchantRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminMerchantIntegrationTests {

	private static final String ADMIN_KEY = "test-admin-key-0123456789";

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void deleteCreatedMerchants() {
		merchantRepository.findAll()
			.stream()
			.filter((merchant) -> !merchant.getId().equals(DEMO_MERCHANT_ID))
			.forEach(merchantRepository::delete);
	}

	@Test
	void createdMerchantIsActiveAndStoresOnlyKeyHash() throws Exception {
		String body = createMerchant(
				"{\"name\": \"Acme Store\", \"email\": \"billing@acme.example\", \"phone\": \"+380501234567\"}");
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
		String apiKey = JsonPath.read(body, "$.apiKey");

		Merchant merchant = merchantRepository.findById(id).orElseThrow();
		assertThat(merchant.getName()).isEqualTo("Acme Store");
		assertThat(merchant.getEmail()).isEqualTo("billing@acme.example");
		assertThat(merchant.getPhone()).isEqualTo("+380501234567");
		assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
		assertThat(merchant.getApiKeyHash()).isEqualTo(ApiKeyHasher.hash(apiKey));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM merchants m WHERE CAST(m AS TEXT) LIKE ?",
				Integer.class, "%" + apiKey + "%"))
			.isZero();
	}

	@Test
	void returnedKeyAuthenticatesNewMerchant() throws Exception {
		String apiKey = JsonPath.read(createMerchant("{\"name\": \"Acme Store\"}"), "$.apiKey");

		mvc.perform(get("/api/v1/payments").header("X-API-Key", apiKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void eachMerchantGetsItsOwnKey() throws Exception {
		String first = JsonPath.read(createMerchant("{\"name\": \"Acme Store\"}"), "$.apiKey");
		String second = JsonPath.read(createMerchant("{\"name\": \"Acme Store\"}"), "$.apiKey");

		assertThat(first).isNotEqualTo(second);
	}

	@Test
	void suspendedMerchantIsBlockedAtOnceAndReactivatedMerchantWorksAgain() throws Exception {
		String body = createMerchant("{\"name\": \"Acme Store\"}");
		String id = JsonPath.read(body, "$.id");
		String apiKey = JsonPath.read(body, "$.apiKey");

		updateStatus(id, "SUSPENDED").andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.status").value("SUSPENDED"));
		assertThat(merchantRepository.findById(UUID.fromString(id)).orElseThrow().getStatus())
			.isEqualTo(MerchantStatus.SUSPENDED);
		mvc.perform(get("/api/v1/payments").header("X-API-Key", apiKey))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("MERCHANT_SUSPENDED"));

		updateStatus(id, "ACTIVE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(get("/api/v1/payments").header("X-API-Key", apiKey)).andExpect(status().isOk());
	}

	@Test
	void settingTheSameStatusAgainIsAllowed() throws Exception {
		String id = JsonPath.read(createMerchant("{\"name\": \"Acme Store\"}"), "$.id");

		updateStatus(id, "ACTIVE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void updateStatusOfUnknownMerchantReturns404() throws Exception {
		UUID unknownId = UUID.randomUUID();

		updateStatus(unknownId.toString(), "SUSPENDED").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("MERCHANT_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("Merchant " + unknownId + " not found"));
	}

	@Test
	void rotatedKeyReplacesOldKeyAtOnce() throws Exception {
		String body = createMerchant("{\"name\": \"Acme Store\"}");
		String id = JsonPath.read(body, "$.id");
		String oldKey = JsonPath.read(body, "$.apiKey");

		String rotated = rotateApiKey(id).andExpect(status().isOk())
			.andExpect(jsonPath("$.merchantId").value(id))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String newKey = JsonPath.read(rotated, "$.apiKey");

		assertThat(newKey).isNotEqualTo(oldKey).hasSize(43);
		assertThat(merchantRepository.findById(UUID.fromString(id)).orElseThrow().getApiKeyHash())
			.isEqualTo(ApiKeyHasher.hash(newKey));
		mvc.perform(get("/api/v1/payments").header("X-API-Key", oldKey))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("INVALID_API_KEY"));
		mvc.perform(get("/api/v1/payments").header("X-API-Key", newKey)).andExpect(status().isOk());
	}

	@Test
	void rotatingKeyOfSuspendedMerchantKeepsItSuspended() throws Exception {
		String id = JsonPath.read(createMerchant("{\"name\": \"Acme Store\"}"), "$.id");
		updateStatus(id, "SUSPENDED").andExpect(status().isOk());

		String newKey = JsonPath.read(rotateApiKey(id).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.apiKey");

		mvc.perform(get("/api/v1/payments").header("X-API-Key", newKey))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("MERCHANT_SUSPENDED"));
	}

	@Test
	void rotatingKeyOfUnknownMerchantReturns404() throws Exception {
		rotateApiKey(UUID.randomUUID().toString()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("MERCHANT_NOT_FOUND"));
	}

	private ResultActions rotateApiKey(String id) throws Exception {
		return mvc.perform(post("/admin/v1/merchants/" + id + "/api-key").header("X-Admin-Key", ADMIN_KEY));
	}

	private ResultActions updateStatus(String id, String status) throws Exception {
		return mvc.perform(patch("/admin/v1/merchants/" + id).header("X-Admin-Key", ADMIN_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\": \"" + status + "\"}"));
	}

	private String createMerchant(String body) throws Exception {
		return mvc
			.perform(post("/admin/v1/merchants").header("X-Admin-Key", ADMIN_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

}
