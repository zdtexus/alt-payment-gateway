package com.example.payment_service.config;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.example.payment_service.TestcontainersConfiguration;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
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
	void usesRelativeServerUrlSoSwaggerCallsTheHostItWasLoadedFrom() throws Exception {
		mvc.perform(get("/v3/api-docs").header("Host", "payment-service:8081"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.servers[*].url", contains("/")));
	}

	@Test
	void describesApiWithPaymentRulesTable() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.info.title").value("Alt Payment Gateway API"))
			.andExpect(jsonPath("$.info.description", containsString("demo-key-123")))
			.andExpect(jsonPath("$.info.description", containsString("| BONUS | EUR | 0.01 | 500.00 |")))
			.andExpect(jsonPath("$.info.description", containsString("| CARD | EUR, PLN, UAH, USD | 0.50 | 10000.00 |")));
	}

	@Test
	void documentsCreatePaymentResponsesAndExamples() throws Exception {
		String create = "$.paths['/api/v1/payments'].post";
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath(create + ".summary").value("Create a payment"))
			.andExpect(jsonPath(create + ".responses.keys()", containsInAnyOrder("201", "400", "401", "403", "422")))
			.andExpect(jsonPath(create + ".responses['201'].headers.Location").exists())
			.andExpect(jsonPath(create + ".responses['201'].content['application/json'].schema['$ref']")
				.value("#/components/schemas/PaymentResponse"))
			.andExpect(jsonPath(create + ".responses['422'].content['application/json'].example.message")
				.value("Amount 600.00 exceeds BONUS limit 500.00"))
			.andExpect(jsonPath("$.components.schemas.CreatePaymentRequest.properties.currency.example").value("EUR"))
			.andExpect(jsonPath("$.components.schemas.CreatePaymentRequest.properties.paymentMethod.example")
				.value("CARD"));
	}

	@Test
	void documentsReadEndpointsResponses() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/v1/payments/{id}'].get.responses.keys()",
					containsInAnyOrder("200", "400", "401", "403", "404")))
			.andExpect(jsonPath("$.paths['/api/v1/payments'].get.responses.keys()",
					containsInAnyOrder("200", "400", "401", "403")))
			.andExpect(jsonPath("$.paths..content['*/*']").isEmpty());
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
