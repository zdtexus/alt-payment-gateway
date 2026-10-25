package com.example.payment_service.notification.sms;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "notification.sms.provider", havingValue = "http")
public class HttpSmsSender implements SmsSender {

	private final RestClient restClient;

	public HttpSmsSender(SmsProperties properties) {
		SmsProperties.Http http = properties.http();
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(http.timeout());
		requestFactory.setReadTimeout(http.timeout());
		RestClient.Builder builder = RestClient.builder().baseUrl(http.url().toString()).requestFactory(requestFactory);
		if (StringUtils.hasText(http.apiKey())) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + http.apiKey());
		}
		this.restClient = builder.build();
	}

	@Override
	public void send(String phone, String message) {
		restClient.post()
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("to", phone, "message", message))
			.retrieve()
			.toBodilessEntity();
	}

}
