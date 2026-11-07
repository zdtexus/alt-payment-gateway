package com.example.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

import com.example.payment_service.entity.Refund;
import com.example.payment_service.entity.RefundStatus;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class RefundDtoJsonTests {

	@Autowired
	private JacksonTester<CreateRefundRequest> requestJson;

	@Autowired
	private JacksonTester<RefundResponse> responseJson;

	@Test
	void writesRefundResponseWithoutProviderDetails() throws Exception {
		Refund refund = new Refund();
		refund.setId(UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"));
		refund.setAmount(new BigDecimal("20.00"));
		refund.setStatus(RefundStatus.DECLINED);
		refund.setReason("Item returned");
		refund.setDeclineReason("Card account closed");
		refund.setProviderReference("sim_r_secret-reference");
		refund.setIdempotencyKey("return-1");
		refund.setRequestHash("a".repeat(64));
		refund.setCreatedAt(Instant.parse("2026-01-16T09:00:00Z"));
		refund.setProcessedAt(Instant.parse("2026-01-16T09:00:01Z"));

		assertThat(responseJson.write(RefundResponse.from(refund,
				UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b"), "EUR")))
			.isStrictlyEqualToJson("""
					{
					  "id": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
					  "paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b",
					  "amount": 20.00,
					  "currency": "EUR",
					  "status": "DECLINED",
					  "reason": "Item returned",
					  "declineReason": "Card account closed",
					  "createdAt": "2026-01-16T09:00:00Z",
					  "processedAt": "2026-01-16T09:00:01Z"
					}
					""");
	}

	@Test
	void readsRefundRequestWithAndWithoutFields() throws Exception {
		CreateRefundRequest partial = requestJson.parseObject("{\"amount\": 20.00, \"reason\": \"Item returned\"}");
		CreateRefundRequest full = requestJson.parseObject("{}");

		assertThat(partial.amount()).isEqualByComparingTo("20.00");
		assertThat(partial.reason()).isEqualTo("Item returned");
		assertThat(full.amount()).isNull();
		assertThat(full.reason()).isNull();
	}

}
