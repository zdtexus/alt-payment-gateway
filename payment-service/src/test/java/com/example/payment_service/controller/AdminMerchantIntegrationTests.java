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

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.repository.MerchantRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
