package com.example.payment_service.config;

import java.util.TreeSet;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.utils.SpringDocUtils;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.payment_service.auth.AdminKeyAuthFilter;
import com.example.payment_service.auth.ApiKeyAuthFilter;
import com.example.payment_service.auth.CurrentMerchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.rules.PaymentMethodRule;
import com.example.payment_service.rules.PaymentRules;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	static final String API_KEY_SCHEME = "ApiKey";

	public static final String ADMIN_KEY_SCHEME = "AdminKey";

	static {
		SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentMerchant.class);
	}

	@Bean
	OpenAPI paymentServiceOpenApi() {
		SecurityScheme apiKey = new SecurityScheme().type(SecurityScheme.Type.APIKEY)
			.in(SecurityScheme.In.HEADER)
			.name(ApiKeyAuthFilter.API_KEY_HEADER)
			.description("Merchant API key, for example `demo-key-123` for the seeded demo merchant");
		SecurityScheme adminKey = new SecurityScheme().type(SecurityScheme.Type.APIKEY)
			.in(SecurityScheme.In.HEADER)
			.name(AdminKeyAuthFilter.ADMIN_KEY_HEADER)
			.description("Admin key from the `ADMIN_API_KEY` setting; only for `/admin/**` endpoints");
		Info info = new Info().title("Alt Payment Gateway API").version("v1").description("""
				Payments API for merchants. Authenticate with the `X-API-Key` header \
				(click **Authorize** and use `demo-key-123` for the demo merchant).

				Merchant administration endpoints under `/admin/v1` use the `X-Admin-Key` header instead. \
				A merchant key never opens them, and the admin key never opens the payments API.

				%s
				Errors use one format: `timestamp`, `status`, `error`, `message`, `path`.
				""".formatted(paymentRulesTable()));
		return new OpenAPI().info(info)
			.addServersItem(new Server().url("/"))
			.components(new Components().addSecuritySchemes(API_KEY_SCHEME, apiKey)
				.addSecuritySchemes(ADMIN_KEY_SCHEME, adminKey))
			.addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
	}

	static String paymentRulesTable() {
		StringBuilder table = new StringBuilder("| Method | Currencies | Minimum | Maximum |\n|---|---|---|---|\n");
		for (PaymentMethod method : PaymentMethod.values()) {
			PaymentMethodRule rule = PaymentRules.forMethod(method);
			table.append("| ").append(method)
				.append(" | ").append(String.join(", ", new TreeSet<>(rule.currencies())))
				.append(" | ").append(rule.minAmount().toPlainString())
				.append(" | ").append(rule.maxAmount().toPlainString())
				.append(" |\n");
		}
		return table.toString();
	}

}
