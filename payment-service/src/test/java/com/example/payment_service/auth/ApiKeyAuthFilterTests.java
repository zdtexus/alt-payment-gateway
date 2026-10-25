package com.example.payment_service.auth;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.repository.MerchantRepository;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiKeyAuthFilterTests.TestController.class)
@Import(ApiKeyAuthFilterTests.TestController.class)
class ApiKeyAuthFilterTests {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private MerchantRepository merchantRepository;

	@Test
	void validKeyReachesController() throws Exception {
		given(merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("active-key")))
			.willReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));

		mvc.perform(get("/api/v1/test").header("X-API-Key", "active-key"))
			.andExpect(status().isOk())
			.andExpect(content().string("ok"));
	}

	@Test
	void controllerReceivesAuthenticatedMerchant() throws Exception {
		given(merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("active-key")))
			.willReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));

		mvc.perform(get("/api/v1/merchant").header("X-API-Key", "active-key"))
			.andExpect(status().isOk())
			.andExpect(content().string("Test Shop"));
	}

	@Test
	void currentMerchantWithoutAuthenticationReturns401() throws Exception {
		mvc.perform(get("/actuator/merchant"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("INVALID_API_KEY"));
	}

	@Test
	void missingKeyReturns401() throws Exception {
		expectError(mvc.perform(get("/api/v1/test")), 401, "INVALID_API_KEY", "Missing or unknown API key");
		verifyNoInteractions(merchantRepository);
	}

	@Test
	void blankKeyReturns401() throws Exception {
		expectError(mvc.perform(get("/api/v1/test").header("X-API-Key", " ")), 401, "INVALID_API_KEY",
				"Missing or unknown API key");
		verifyNoInteractions(merchantRepository);
	}

	@Test
	void unknownKeyReturns401() throws Exception {
		given(merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("unknown-key")))
			.willReturn(Optional.empty());

		expectError(mvc.perform(get("/api/v1/test").header("X-API-Key", "unknown-key")), 401, "INVALID_API_KEY",
				"Missing or unknown API key");
	}

	@Test
	void suspendedMerchantReturns403() throws Exception {
		given(merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("suspended-key")))
			.willReturn(Optional.of(merchant(MerchantStatus.SUSPENDED)));

		expectError(mvc.perform(get("/api/v1/test").header("X-API-Key", "suspended-key")), 403,
				"MERCHANT_SUSPENDED", "Merchant account is suspended");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/actuator", "/actuator/health", "/v3/api-docs", "/v3/api-docs/swagger-config",
			"/swagger-ui/index.html", "/swagger-ui.html" })
	void publicPathsDoNotRequireKey(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string("ok"));
		verifyNoInteractions(merchantRepository);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/actuator/health", "/actuator-admin" })
	void similarPathsStillRequireKey(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isUnauthorized());
	}

	private static void expectError(ResultActions result, int status, String error, String message)
			throws Exception {
		result.andExpect(status().is(status))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.timestamp").isNotEmpty())
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.error").value(error))
			.andExpect(jsonPath("$.message").value(message))
			.andExpect(jsonPath("$.path").value("/api/v1/test"));
	}

	private static Merchant merchant(MerchantStatus status) {
		Merchant merchant = new Merchant();
		merchant.setName("Test Shop");
		merchant.setStatus(status);
		return merchant;
	}

	@RestController
	static class TestController {

		@GetMapping({ "/api/v1/test", "/actuator", "/actuator/health", "/v3/api-docs", "/v3/api-docs/swagger-config",
				"/swagger-ui/index.html", "/swagger-ui.html", "/api/v1/actuator/health", "/actuator-admin" })
		String ok() {
			return "ok";
		}

		@GetMapping({ "/api/v1/merchant", "/actuator/merchant" })
		String merchantName(@CurrentMerchant Merchant merchant) {
			return merchant.getName();
		}

	}

}
