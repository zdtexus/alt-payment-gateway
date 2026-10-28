package com.example.api_gateway.ratelimit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitExceededFilterTests {

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = { "10, 1, 1", "1, 1, 1", "1, 5, 5", "2, 5, 3", "10, 25, 3", "0, 1, 1",
			"null, 1, 1", "abc, 1, 1", "1, null, 1" })
	void retryAfterIsTimeToRefillRequestedTokensRoundedUp(String replenishRate, String requestedTokens,
			long expectedSeconds) {
		HttpHeaders headers = new HttpHeaders();
		if (replenishRate != null) {
			headers.set("X-RateLimit-Replenish-Rate", replenishRate);
		}
		if (requestedTokens != null) {
			headers.set("X-RateLimit-Requested-Tokens", requestedTokens);
		}

		assertThat(RateLimitExceededFilter.retryAfterSeconds(headers)).isEqualTo(expectedSeconds);
	}

}
