package com.example.payment_service.auth;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.error.InvalidApiKeyException;
import com.example.payment_service.error.MerchantSuspendedException;
import com.example.payment_service.repository.MerchantRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
		given(merchantRepository.findByApiKey("active-key")).willReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));

		mvc.perform(get("/api/v1/test").header("X-API-Key", "active-key"))
			.andExpect(status().isOk())
			.andExpect(content().string("ok"));
	}

	@Test
	void missingKeyIsRejected() {
		assertThatThrownBy(() -> mvc.perform(get("/api/v1/test"))).isInstanceOf(InvalidApiKeyException.class);
		verifyNoInteractions(merchantRepository);
	}

	@Test
	void blankKeyIsRejected() {
		assertThatThrownBy(() -> mvc.perform(get("/api/v1/test").header("X-API-Key", " ")))
			.isInstanceOf(InvalidApiKeyException.class);
		verifyNoInteractions(merchantRepository);
	}

	@Test
	void unknownKeyIsRejected() {
		given(merchantRepository.findByApiKey("unknown-key")).willReturn(Optional.empty());

		assertThatThrownBy(() -> mvc.perform(get("/api/v1/test").header("X-API-Key", "unknown-key")))
			.isInstanceOf(InvalidApiKeyException.class);
	}

	@Test
	void suspendedMerchantIsRejected() {
		given(merchantRepository.findByApiKey("suspended-key"))
			.willReturn(Optional.of(merchant(MerchantStatus.SUSPENDED)));

		assertThatThrownBy(() -> mvc.perform(get("/api/v1/test").header("X-API-Key", "suspended-key")))
			.isInstanceOf(MerchantSuspendedException.class);
	}

	private static Merchant merchant(MerchantStatus status) {
		Merchant merchant = new Merchant();
		merchant.setName("Test Shop");
		merchant.setStatus(status);
		return merchant;
	}

	@RestController
	static class TestController {

		@GetMapping("/api/v1/test")
		String ok() {
			return "ok";
		}

	}

}
