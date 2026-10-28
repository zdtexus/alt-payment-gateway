package com.example.payment_service.provider;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.example.payment_service.TestcontainersConfiguration;
import com.example.payment_service.auth.ApiKeyHasher;
import com.example.payment_service.dto.CreatePaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Merchant;
import com.example.payment_service.entity.PaymentMethod;
import com.example.payment_service.entity.Transaction;
import com.example.payment_service.entity.TransactionStatus;
import com.example.payment_service.event.PaymentProcessedEvent;
import com.example.payment_service.repository.MerchantRepository;
import com.example.payment_service.repository.TransactionRepository;
import com.example.payment_service.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "payment.provider.routes.CARD=http", "payment.provider.http.read-timeout=300ms" })
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class HttpPaymentProviderIntegrationTests {

	private static final HttpServer server = startServer();

	private static volatile int responseStatus;

	private static volatile String responseBody;

	private static volatile Duration responseDelay;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private ApplicationEvents events;

	@DynamicPropertySource
	static void providerUrl(DynamicPropertyRegistry registry) {
		registry.add("payment.provider.http.url",
				() -> "http://localhost:" + server.getAddress().getPort() + "/pay");
	}

	@AfterEach
	void reset() {
		transactionRepository.deleteAll();
		answer(200, "{}", Duration.ZERO);
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@Test
	void approvedAnswerSucceedsWithProviderReference() {
		answer(200, """
				{"status":"APPROVED","reference":"acme_1"}""", Duration.ZERO);

		Transaction stored = pay();

		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
		assertThat(stored.getProvider()).isEqualTo("http");
		assertThat(stored.getProviderReference()).isEqualTo("acme_1");
		assertThat(events.stream(PaymentProcessedEvent.class)).hasSize(1);
	}

	@Test
	void declinedAnswerIsDeclined() {
		answer(200, """
				{"status":"DECLINED","reference":"acme_2","reason":"Card expired"}""", Duration.ZERO);

		Transaction stored = pay();

		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.DECLINED);
		assertThat(stored.getDeclineReason()).isEqualTo("Card expired");
	}

	@Test
	void timeoutLeavesPaymentPendingInsteadOfDeclining() {
		answer(200, """
				{"status":"DECLINED","reference":"acme_late","reason":"Too late"}""", Duration.ofSeconds(2));

		long start = System.nanoTime();
		Transaction stored = pay();

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
		assertThat(stored.getStatus()).isEqualTo(TransactionStatus.PENDING);
		assertThat(stored.getProvider()).isEqualTo("http");
		assertThat(stored.getProviderReference()).isNull();
		assertThat(stored.getDeclineReason()).isNull();
		assertThat(stored.getProcessedAt()).isNull();
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void serverErrorLeavesPaymentPending() {
		answer(503, "{}", Duration.ZERO);

		assertThat(pay().getStatus()).isEqualTo(TransactionStatus.PENDING);
		assertThat(events.stream(PaymentProcessedEvent.class)).isEmpty();
	}

	@Test
	void unreadableAnswerLeavesPaymentPending() {
		answer(200, """
				{"status":"MAYBE"}""", Duration.ZERO);

		assertThat(pay().getStatus()).isEqualTo(TransactionStatus.PENDING);
	}

	private Transaction pay() {
		Merchant merchant = merchantRepository.findByApiKeyHash(ApiKeyHasher.hash("demo-key-123")).orElseThrow();
		PaymentResponse response = paymentService.process(merchant,
				new CreatePaymentRequest(new BigDecimal("49.90"), "EUR", PaymentMethod.CARD));
		Transaction stored = transactionRepository.findById(response.id()).orElseThrow();
		assertThat(response.status()).isEqualTo(stored.getStatus());
		return stored;
	}

	private static void answer(int status, String body, Duration delay) {
		responseStatus = status;
		responseBody = body;
		responseDelay = delay;
	}

	private static HttpServer startServer() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/pay", (exchange) -> {
				exchange.getRequestBody().readAllBytes();
				try {
					Thread.sleep(responseDelay);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(responseStatus, bytes.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
				catch (IOException ex) {
					exchange.close();
				}
			});
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			server.start();
			answer(200, "{}", Duration.ZERO);
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
