package com.example.payment_service.notification.sms;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SmsPropertiesTests {

	private static final URI URL = URI.create("https://sms.example.com/send");

	@Test
	void toStringMasksApiKey() {
		SmsProperties.Http http = new SmsProperties.Http(URL, "sms-secret-key", Duration.ofSeconds(5));

		assertThat(http).hasToString("Http[url=https://sms.example.com/send, apiKey=***, timeout=PT5S]");
		assertThat(http.toString()).doesNotContain("sms-secret-key");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "  " })
	void toStringShowsMissingApiKeyAsNull(String apiKey) {
		assertThat(new SmsProperties.Http(URL, apiKey, Duration.ofSeconds(5)))
			.hasToString("Http[url=https://sms.example.com/send, apiKey=null, timeout=PT5S]");
	}

	@Test
	void outerToStringAlsoMasksApiKey() {
		SmsProperties properties = new SmsProperties(SmsProperties.Provider.HTTP,
				new SmsProperties.Http(URL, "sms-secret-key", Duration.ofSeconds(5)));

		assertThat(properties.toString()).doesNotContain("sms-secret-key").contains("apiKey=***").contains("HTTP");
	}

}
