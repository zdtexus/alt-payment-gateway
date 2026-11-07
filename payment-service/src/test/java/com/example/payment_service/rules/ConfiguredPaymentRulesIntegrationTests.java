package com.example.payment_service.rules;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.payment_service.TestcontainersConfiguration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "PAYMENT_RULES_BONUS_MAX_AMOUNT=100.00")
class ConfiguredPaymentRulesIntegrationTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void amountAboveTheConfiguredMaximumIsRejected() throws Exception {
		pay("150.00", "BONUS").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error").value("PAYMENT_RULE_VIOLATION"))
			.andExpect(jsonPath("$.message").value("Amount 150.00 exceeds BONUS limit 100.00"));
	}

	@Test
	void amountAtTheConfiguredMaximumIsAccepted() throws Exception {
		pay("100.00", "BONUS").andExpect(status().isCreated()).andExpect(jsonPath("$.paymentMethod").value("BONUS"));
	}

	@Test
	void otherMethodsKeepTheirDefaults() throws Exception {
		pay("150.00", "CARD").andExpect(status().isCreated());
		pay("10000.01", "CARD").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.message").value("Amount 10000.01 exceeds CARD limit 10000.00"));
	}

	@Test
	void apiDescriptionShowsTheConfiguredRule() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.info.description", containsString("| BONUS | EUR | 0.01 | 100.00 |")))
			.andExpect(jsonPath("$.info.description", not(containsString("| BONUS | EUR | 0.01 | 500.00 |"))))
			.andExpect(jsonPath("$.info.description", containsString("| CARD | EUR, PLN, UAH, USD | 0.50 | 10000.00 |")));
	}

	private ResultActions pay(String amount, String method) throws Exception {
		return mvc.perform(post("/api/v1/payments").header("X-API-Key", "demo-key-123")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\": " + amount + ", \"currency\": \"EUR\", \"paymentMethod\": \"" + method + "\"}"));
	}

}
