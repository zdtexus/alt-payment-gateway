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
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
	void webhookEndpointHasNoApiKeySecurityAndDocumentsResponses() throws Exception {
		String webhook = "$.paths['/api/v1/providers/{name}/webhook'].post";
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath(webhook + ".security", empty()))
			.andExpect(jsonPath(webhook + ".tags", contains("Provider webhooks")))
			.andExpect(jsonPath(webhook + ".parameters[*].name", hasItem("X-Webhook-Signature")))
			.andExpect(jsonPath(webhook + ".responses.keys()", containsInAnyOrder("204", "400", "401", "404")));
	}

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
	void adminEndpointsUseOnlyAdminKeyScheme() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.components.securitySchemes.AdminKey.type").value("apiKey"))
			.andExpect(jsonPath("$.components.securitySchemes.AdminKey.in").value("header"))
			.andExpect(jsonPath("$.components.securitySchemes.AdminKey.name").value("X-Admin-Key"))
			.andExpect(jsonPath("$.paths['/admin/v1/merchants'].post.security", contains(hasKey("AdminKey"))))
			.andExpect(jsonPath("$.paths['/admin/v1/merchants/{id}'].patch.security", contains(hasKey("AdminKey"))))
			.andExpect(jsonPath("$.paths['/admin/v1/merchants/{id}/api-key'].post.security",
					contains(hasKey("AdminKey"))))
			.andExpect(jsonPath("$.paths['/admin/v1/merchants'].post.security[*].ApiKey").isEmpty())
			.andExpect(jsonPath("$.paths['/api/v1/payments'].post.security").doesNotExist())
			.andExpect(jsonPath("$.info.description", containsString("X-Admin-Key")));
	}

	@Test
	void documentsAdminEndpointsResponses() throws Exception {
		String create = "$.paths['/admin/v1/merchants'].post";
		String status = "$.paths['/admin/v1/merchants/{id}'].patch";
		String rotate = "$.paths['/admin/v1/merchants/{id}/api-key'].post";
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath(create + ".tags", contains("Merchant administration")))
			.andExpect(jsonPath(create + ".responses.keys()", containsInAnyOrder("201", "400", "401")))
			.andExpect(jsonPath(create + ".responses['201'].headers['Cache-Control']").exists())
			.andExpect(jsonPath(create + ".responses['201'].content['application/json'].schema['$ref']")
				.value("#/components/schemas/CreatedMerchantResponse"))
			.andExpect(jsonPath(create + ".responses['401'].content['application/json'].example.error")
				.value("INVALID_ADMIN_KEY"))
			.andExpect(jsonPath(status + ".responses.keys()", containsInAnyOrder("200", "400", "401", "404")))
			.andExpect(jsonPath(status + ".responses['200'].content['application/json'].schema['$ref']")
				.value("#/components/schemas/MerchantResponse"))
			.andExpect(jsonPath(status + ".responses['404'].content['application/json'].example.error")
				.value("MERCHANT_NOT_FOUND"))
			.andExpect(jsonPath(rotate + ".responses.keys()", containsInAnyOrder("200", "400", "401", "404")))
			.andExpect(jsonPath(rotate + ".responses['200'].headers['Cache-Control']").exists())
			.andExpect(jsonPath(rotate + ".responses['200'].content['application/json'].schema['$ref']")
				.value("#/components/schemas/ApiKeyResponse"))
			.andExpect(jsonPath("$.components.schemas.UpdateMerchantStatusRequest.properties.status.enum",
					containsInAnyOrder("ACTIVE", "SUSPENDED")))
			.andExpect(jsonPath("$.components.schemas.CreateMerchantRequest.required", contains("name")))
			.andExpect(jsonPath("$.components.schemas..apiKeyHash").isEmpty());
	}

	@Test
	void paymentResponseDoesNotExposeProviderDetails() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.components.schemas.PaymentResponse.properties.keys()",
					containsInAnyOrder("id", "amount", "currency", "paymentMethod", "status", "declineReason",
							"createdAt", "processedAt")))
			.andExpect(jsonPath("$..provider").isEmpty())
			.andExpect(jsonPath("$..providerReference").isEmpty());
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
			.andExpect(jsonPath(create + ".responses['201'].headers['Idempotent-Replayed']").exists())
			.andExpect(jsonPath(create + ".parameters[?(@.name == 'Idempotency-Key')].in").value("header"))
			.andExpect(jsonPath(create + ".parameters[?(@.name == 'Idempotency-Key')].required").value(false))
			.andExpect(jsonPath(create + ".parameters[?(@.name == 'Idempotency-Key')].schema.maxLength").value(255))
			.andExpect(jsonPath(create
					+ ".responses['422'].content['application/json'].examples.PAYMENT_RULE_VIOLATION.value.message")
				.value("Amount 600.00 exceeds BONUS limit 500.00"))
			.andExpect(jsonPath(create
					+ ".responses['422'].content['application/json'].examples.IDEMPOTENCY_KEY_REUSED.value.error")
				.value("IDEMPOTENCY_KEY_REUSED"))
			.andExpect(jsonPath("$.components.schemas.CreatePaymentRequest.properties.currency.example").value("EUR"))
			.andExpect(jsonPath("$.components.schemas.CreatePaymentRequest.properties.paymentMethod.example")
				.value("CARD"));
	}

	@Test
	void documentsPendingFlow() throws Exception {
		String created = "$.paths['/api/v1/payments'].post.responses['201'].content['application/json'].examples";
		String response = "$.components.schemas.PaymentResponse.properties";
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath(created + ".keys()", containsInAnyOrder("SUCCEEDED", "DECLINED", "PENDING")))
			.andExpect(jsonPath(created + ".PENDING.value.status").value("PENDING"))
			.andExpect(jsonPath(created + ".PENDING.value.processedAt").value(nullValue()))
			.andExpect(jsonPath(created + ".DECLINED.value.declineReason").value("Insufficient funds"))
			.andExpect(jsonPath(response + ".status.description", containsString("PENDING until")))
			.andExpect(jsonPath(response + ".status.enum", containsInAnyOrder("PENDING", "SUCCEEDED", "DECLINED")))
			.andExpect(jsonPath(response + ".processedAt.description", containsString("null while PENDING")))
			.andExpect(jsonPath("$.paths['/api/v1/payments'].post.description",
					containsString("receipt once the payment is SUCCEEDED or DECLINED")))
			.andExpect(jsonPath("$.paths['/api/v1/payments'].get.parameters[?(@.name == 'status')].description",
					contains(containsString("PENDING"))))
			.andExpect(jsonPath("$.info.description", containsString("POST /api/v1/providers/{name}/webhook")));
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
