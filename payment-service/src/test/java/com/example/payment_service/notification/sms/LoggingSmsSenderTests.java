package com.example.payment_service.notification.sms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LoggingSmsSenderTests {

	private final LoggingSmsSender sender = new LoggingSmsSender();

	@Test
	void logsTextWithOnlyTheLastFourDigitsOfThePhone(CapturedOutput output) {
		sender.send("+380501112233", "Payment 49.90 EUR succeeded");

		assertThat(output).contains("SMS to ***2233: Payment 49.90 EUR succeeded")
			.doesNotContain("+380501112233")
			.doesNotContain("380501112233")
			.doesNotContain("0501112233");
	}

	@ParameterizedTest
	@CsvSource({ "+380501112233, ***2233", "+15551234567, ***4567", "12345, ***2345", "1234, ***", "12, ***" })
	void keepsOnlyTheLastFourDigits(String phone, String masked) {
		assertThat(LoggingSmsSender.mask(phone)).isEqualTo(masked);
	}

	@ParameterizedTest
	@NullAndEmptySource
	void hidesMissingPhoneCompletely(String phone) {
		assertThat(LoggingSmsSender.mask(phone)).isEqualTo("***");
	}

}
