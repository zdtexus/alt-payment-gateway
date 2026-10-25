package com.example.payment_service.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.utils.SpringDocUtils;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.payment_service.auth.ApiKeyAuthFilter;
import com.example.payment_service.auth.CurrentMerchant;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	static final String API_KEY_SCHEME = "ApiKey";

	static {
		SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentMerchant.class);
	}

	@Bean
	OpenAPI paymentServiceOpenApi() {
		SecurityScheme apiKey = new SecurityScheme().type(SecurityScheme.Type.APIKEY)
			.in(SecurityScheme.In.HEADER)
			.name(ApiKeyAuthFilter.API_KEY_HEADER)
			.description("Merchant API key, for example `demo-key-123` for the seeded demo merchant");
		return new OpenAPI().addServersItem(new Server().url("/"))
			.components(new Components().addSecuritySchemes(API_KEY_SCHEME, apiKey))
			.addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
	}

}
