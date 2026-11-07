package com.example.payment_service.reconciliation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.notification.email.EmailSender;
import com.example.payment_service.notification.sms.SmsSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import({ TestcontainersConfiguration.class, StubStatusProviderConfig.class })
@TestPropertySource(properties = { "payment.reconciliation.enabled=true", "PAYMENT_RECONCILIATION_INTERVAL=1s" })
@ExtendWith(OutputCaptureExtension.class)
class ReconciliationScheduleTests {

	private static final UUID DEMO_MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private EmailSender emailSender;

	@MockitoBean
	private SmsSender smsSender;

	@Test
	void theScheduleSettlesADuePaymentWithoutBeingCalled(CapturedOutput output) throws InterruptedException {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO transactions (id, merchant_id, amount, currency, payment_method, status, provider,
				                          provider_reference, created_at, next_check_at, check_attempts)
				VALUES (?, ?, 49.90, 'EUR', 'CARD', 'PENDING', ?, 'ref-s', ?, ?, 0)
				""", id, DEMO_MERCHANT_ID, StubStatusProviderConfig.StubStatusProvider.NAME,
				Timestamp.from(Instant.now().minusSeconds(600)), Timestamp.from(Instant.now().minusSeconds(1)));

		String status = "PENDING";
		Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
		while (status.equals("PENDING") && Instant.now().isBefore(deadline)) {
			Thread.sleep(200);
			status = jdbcTemplate.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, id);
		}

		assertThat(status).isEqualTo("SUCCEEDED");
		verify(emailSender, timeout(5000)).send(any(), eq("Payment " + id + " succeeded"), any());
		assertThat(output).contains("Pending payments are checked with their providers every PT1S "
				+ "(first check PT2M after creation, backoff up to PT1H, alert after PT24H)");
	}

}
