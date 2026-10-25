package com.example.payment_service.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.repository.MerchantRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MerchantAuthIntegrationTests.TestController.class })
class MerchantAuthIntegrationTests {

	private static final String DEMO_KEY = "demo-key-123";

	private static final String SUSPENDED_KEY = "suspended-key-456";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MerchantRepository merchantRepository;

	private Merchant suspendedMerchant;

	@BeforeEach
	void createSuspendedMerchant() {
		Merchant merchant = new Merchant();
		merchant.setName("Suspended Shop");
		merchant.setApiKey(SUSPENDED_KEY);
		merchant.setStatus(MerchantStatus.SUSPENDED);
		suspendedMerchant = merchantRepository.save(merchant);
	}

	@AfterEach
	void deleteSuspendedMerchant() {
		merchantRepository.delete(suspendedMerchant);
	}

	@Test
	void validKeyAuthenticatesSeededMerchant() throws Exception {
		mvc.perform(get("/api/v1/test/merchant").header("X-API-Key", DEMO_KEY))
			.andExpect(status().isOk())
			.andExpect(content().string("Demo Shop"));
	}

	@Test
	void missingKeyReturns401() throws Exception {
		expectError(mvc.perform(get("/api/v1/test/merchant")), 401, "INVALID_API_KEY", "Missing or unknown API key");
	}

	@Test
	void unknownKeyReturns401() throws Exception {
		expectError(mvc.perform(get("/api/v1/test/merchant").header("X-API-Key", "unknown-key")), 401,
				"INVALID_API_KEY", "Missing or unknown API key");
	}

	@Test
	void suspendedMerchantReturns403() throws Exception {
		expectError(mvc.perform(get("/api/v1/test/merchant").header("X-API-Key", SUSPENDED_KEY)), 403,
				"MERCHANT_SUSPENDED", "Merchant account is suspended");
	}

	@Test
	void healthEndpointDoesNotRequireKey() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

	private static void expectError(ResultActions result, int status, String error, String message)
			throws Exception {
		result.andExpect(status().is(status))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.timestamp").isNotEmpty())
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.error").value(error))
			.andExpect(jsonPath("$.message").value(message))
			.andExpect(jsonPath("$.path").value("/api/v1/test/merchant"));
	}

	@RestController
	static class TestController {

		@GetMapping("/api/v1/test/merchant")
		String merchantName(@CurrentMerchant Merchant merchant) {
			return merchant.getName();
		}

	}

}
