package com.example.payment_service.provider;

import java.net.http.HttpClient;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "payment.provider.http.url")
public class HttpPaymentProvider implements PaymentProvider {

	public static final String NAME = "http";

	private final RestClient restClient;

	public HttpPaymentProvider(HttpPaymentProviderProperties properties) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient(properties));
		requestFactory.setReadTimeout(properties.readTimeout());
		RestClient.Builder builder = RestClient.builder()
			.baseUrl(properties.url().toString())
			.requestFactory(new BufferingClientHttpRequestFactory(requestFactory));
		if (StringUtils.hasText(properties.apiKey())) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
		}
		this.restClient = builder.build();
	}

	static HttpClient httpClient(HttpPaymentProviderProperties properties) {
		return HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(properties.connectTimeout())
			.build();
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public ProviderResult process(ProviderRequest request) {
		ProviderAnswer answer = restClient.post()
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", request.paymentId().toString())
			.body(Map.of("paymentId", request.paymentId(), "method", request.method(), "currency",
					request.currency(), "amount", request.amount()))
			.retrieve()
			.body(ProviderAnswer.class);
		if (answer == null || answer.status() == null) {
			throw new IllegalStateException("Payment provider http answered without a status");
		}
		return switch (answer.status()) {
			case "APPROVED" -> ProviderResult.approved(answer.reference());
			case "DECLINED" -> ProviderResult.declined(answer.reason(), answer.reference());
			case "PENDING" -> ProviderResult.pending(answer.reference());
			default -> throw new IllegalStateException(
					"Payment provider http answered unknown status '" + answer.status() + "'");
		};
	}

	record ProviderAnswer(String status, String reference, String reason) {
	}

}
