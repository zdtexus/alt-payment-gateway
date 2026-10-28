package com.example.payment_service.controller;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.payment_service.dto.ApiKeyResponse;
import com.example.payment_service.dto.CreateMerchantRequest;
import com.example.payment_service.dto.CreatedMerchantResponse;
import com.example.payment_service.dto.MerchantResponse;
import com.example.payment_service.entity.MerchantStatus;
import com.example.payment_service.error.MerchantNotFoundException;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.service.MerchantAdminService;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminMerchantController.class)
class AdminMerchantControllerTests {

	private static final String ADMIN_KEY = "test-admin-key-0123456789";

	private static final UUID MERCHANT_ID = UUID.fromString("3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f");

	private static final String NEW_KEY = "q3Zb8kV1xYwP0nR7sT2uL5mH9cJ4dF6gA1eB3iK8oQ0";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private MerchantAdminService merchantAdminService;

	@MockitoBean
	private MerchantRepository merchantRepository;

	@Test
	void createReturns201WithKeyAndNoStore() throws Exception {
		CreateMerchantRequest request = new CreateMerchantRequest("Acme Store", "billing@acme.example",
				"+380501234567");
		given(merchantAdminService.create(request)).willReturn(new CreatedMerchantResponse(MERCHANT_ID, "Acme Store",
				"billing@acme.example", "+380501234567", MerchantStatus.ACTIVE, NEW_KEY));

		mvc.perform(create("{\"name\": \"Acme Store\", \"email\": \"billing@acme.example\", "
				+ "\"phone\": \"+380501234567\"}"))
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.id").value(MERCHANT_ID.toString()))
			.andExpect(jsonPath("$.name").value("Acme Store"))
			.andExpect(jsonPath("$.email").value("billing@acme.example"))
			.andExpect(jsonPath("$.phone").value("+380501234567"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.apiKey").value(NEW_KEY))
			.andExpect(jsonPath("$.apiKeyHash").doesNotExist());
	}

	@Test
	void createWithOnlyNameReturns201() throws Exception {
		CreateMerchantRequest request = new CreateMerchantRequest("Acme Store", null, null);
		given(merchantAdminService.create(request)).willReturn(
				new CreatedMerchantResponse(MERCHANT_ID, "Acme Store", null, null, MerchantStatus.ACTIVE, NEW_KEY));

		mvc.perform(create("{\"name\": \"Acme Store\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value(nullValue()))
			.andExpect(jsonPath("$.phone").value(nullValue()));
	}

	@Test
	void invalidBodyReturns400WithAllFieldErrors() throws Exception {
		mvc.perform(create("{\"name\": \" \", \"email\": \"not-an-email\", \"phone\": \"0501234567\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("email: must be a well-formed email address; "
					+ "name: must not be blank; phone: must be in E.164 format, for example +380501234567"))
			.andExpect(jsonPath("$.path").value("/admin/v1/merchants"));
		verifyNoInteractions(merchantAdminService);
	}

	@Test
	void malformedBodyReturns400() throws Exception {
		mvc.perform(create("{\"name\":"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("Malformed request body"));
		verifyNoInteractions(merchantAdminService);
	}

	@Test
	void missingAdminKeyReturns401() throws Exception {
		mvc.perform(post("/admin/v1/merchants").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Acme Store\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("INVALID_ADMIN_KEY"));
		verifyNoInteractions(merchantAdminService);
	}

	@Test
	void merchantKeyReturns401() throws Exception {
		mvc.perform(post("/admin/v1/merchants").header("X-API-Key", "demo-key-123")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Acme Store\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("INVALID_ADMIN_KEY"));
		verifyNoInteractions(merchantAdminService);
	}

	@ParameterizedTest
	@EnumSource(MerchantStatus.class)
	void updateStatusReturns200WithMerchant(MerchantStatus status) throws Exception {
		given(merchantAdminService.updateStatus(MERCHANT_ID, status)).willReturn(new MerchantResponse(MERCHANT_ID,
				"Acme Store", "billing@acme.example", "+380501234567", status));

		mvc.perform(updateStatus(MERCHANT_ID.toString(), "{\"status\": \"" + status + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(MERCHANT_ID.toString()))
			.andExpect(jsonPath("$.name").value("Acme Store"))
			.andExpect(jsonPath("$.status").value(status.name()))
			.andExpect(jsonPath("$.apiKey").doesNotExist())
			.andExpect(jsonPath("$.apiKeyHash").doesNotExist());
	}

	@Test
	void updateStatusOfUnknownMerchantReturns404() throws Exception {
		given(merchantAdminService.updateStatus(MERCHANT_ID, MerchantStatus.SUSPENDED))
			.willThrow(new MerchantNotFoundException(MERCHANT_ID));

		mvc.perform(updateStatus(MERCHANT_ID.toString(), "{\"status\": \"SUSPENDED\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("MERCHANT_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("Merchant " + MERCHANT_ID + " not found"))
			.andExpect(jsonPath("$.path").value("/admin/v1/merchants/" + MERCHANT_ID));
	}

	@Test
	void updateStatusWithoutStatusReturns400() throws Exception {
		mvc.perform(updateStatus(MERCHANT_ID.toString(), "{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("status: must not be null"));
		verifyNoInteractions(merchantAdminService);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"status\": \"DELETED\"}", "{\"status\": \"active\"}", "{\"status\":" })
	void updateStatusWithUnknownStatusReturns400(String body) throws Exception {
		mvc.perform(updateStatus(MERCHANT_ID.toString(), body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("Malformed request body"));
		verifyNoInteractions(merchantAdminService);
	}

	@Test
	void updateStatusWithInvalidIdReturns400() throws Exception {
		mvc.perform(updateStatus("not-a-uuid", "{\"status\": \"SUSPENDED\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("id: invalid value"));
		verifyNoInteractions(merchantAdminService);
	}

	@Test
	void updateStatusWithoutAdminKeyReturns401() throws Exception {
		mvc.perform(patch("/admin/v1/merchants/" + MERCHANT_ID).contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\": \"ACTIVE\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("INVALID_ADMIN_KEY"));
		verifyNoInteractions(merchantAdminService);
	}

	@Test
	void rotateApiKeyReturns200WithNewKeyAndNoStore() throws Exception {
		given(merchantAdminService.rotateApiKey(MERCHANT_ID)).willReturn(new ApiKeyResponse(MERCHANT_ID, NEW_KEY));

		mvc.perform(post("/admin/v1/merchants/" + MERCHANT_ID + "/api-key").header("X-Admin-Key", ADMIN_KEY))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.merchantId").value(MERCHANT_ID.toString()))
			.andExpect(jsonPath("$.apiKey").value(NEW_KEY));
	}

	@Test
	void rotateApiKeyOfUnknownMerchantReturns404() throws Exception {
		given(merchantAdminService.rotateApiKey(MERCHANT_ID)).willThrow(new MerchantNotFoundException(MERCHANT_ID));

		mvc.perform(post("/admin/v1/merchants/" + MERCHANT_ID + "/api-key").header("X-Admin-Key", ADMIN_KEY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("MERCHANT_NOT_FOUND"));
	}

	@Test
	void rotateApiKeyWithInvalidIdReturns400() throws Exception {
		mvc.perform(post("/admin/v1/merchants/not-a-uuid/api-key").header("X-Admin-Key", ADMIN_KEY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("id: invalid value"));
		verifyNoInteractions(merchantAdminService);
	}

	@Test
	void rotateApiKeyWithoutAdminKeyReturns401() throws Exception {
		mvc.perform(post("/admin/v1/merchants/" + MERCHANT_ID + "/api-key").header("X-API-Key", "demo-key-123"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("INVALID_ADMIN_KEY"));
		verifyNoInteractions(merchantAdminService);
	}

	private static MockHttpServletRequestBuilder create(String body) {
		return post("/admin/v1/merchants").header("X-Admin-Key", ADMIN_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private static MockHttpServletRequestBuilder updateStatus(String id, String body) {
		return patch("/admin/v1/merchants/" + id).header("X-Admin-Key", ADMIN_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

}
