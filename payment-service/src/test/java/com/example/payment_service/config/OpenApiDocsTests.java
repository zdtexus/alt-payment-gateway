package com.example.payment_service.config;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.example.payment_service.TestcontainersConfiguration;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void declaresApiKeyHeaderScheme() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.components.securitySchemes.ApiKey.type").value("apiKey"))
			.andExpect(jsonPath("$.components.securitySchemes.ApiKey.in").value("header"))
			.andExpect(jsonPath("$.components.securitySchemes.ApiKey.name").value("X-API-Key"))
			.andExpect(jsonPath("$.security[*].ApiKey", contains(empty())));
	}

	@Test
	void hidesCurrentMerchantParameter() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/v1/payments'].get.parameters[*].name", hasItem("status")))
			.andExpect(jsonPath("$.paths..parameters[*].name", everyItem(not("merchant"))))
			.andExpect(jsonPath("$.components.schemas.Merchant").doesNotExist());
	}

}
