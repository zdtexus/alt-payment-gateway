package com.example.payment_service.auth;

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

import com.example.payment_service.repository.MerchantRepository;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminKeyAuthFilterTests.TestController.class)
@Import(AdminKeyAuthFilterTests.TestController.class)
class AdminKeyAuthFilterTests {

	private static final String ADMIN_KEY = "test-admin-key-0123456789";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private MerchantRepository merchantRepository;

	@Test
	void validAdminKeyReachesControllerWithoutMerchantKey() throws Exception {
		mvc.perform(get("/admin/v1/test").header("X-Admin-Key", ADMIN_KEY))
			.andExpect(status().isOk())
			.andExpect(content().string("admin"));
		verifyNoInteractions(merchantRepository);
	}

	@Test
	void missingAdminKeyReturns401() throws Exception {
		expectInvalidAdminKey(mvc.perform(get("/admin/v1/test")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "wrong", "test-admin-key-012345678", "test-admin-key-01234567890",
			"TEST-ADMIN-KEY-0123456789" })
	void wrongAdminKeyReturns401(String adminKey) throws Exception {
		expectInvalidAdminKey(mvc.perform(get("/admin/v1/test").header("X-Admin-Key", adminKey)));
	}

	@Test
	void merchantKeyDoesNotOpenAdminPaths() throws Exception {
		expectInvalidAdminKey(mvc.perform(get("/admin/v1/test").header("X-API-Key", "demo-key-123")));
		verifyNoInteractions(merchantRepository);
	}

	@Test
	void adminKeyDoesNotOpenMerchantPaths() throws Exception {
		mvc.perform(get("/api/v1/test").header("X-Admin-Key", ADMIN_KEY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("INVALID_API_KEY"));
	}

	private static void expectInvalidAdminKey(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.error").value("INVALID_ADMIN_KEY"))
			.andExpect(jsonPath("$.message").value("Missing or invalid admin key"))
			.andExpect(jsonPath("$.path").value("/admin/v1/test"));
	}

	@RestController
	static class TestController {

		@GetMapping("/admin/v1/test")
		String admin() {
			return "admin";
		}

		@GetMapping("/api/v1/test")
		String api() {
			return "api";
		}

	}

}
