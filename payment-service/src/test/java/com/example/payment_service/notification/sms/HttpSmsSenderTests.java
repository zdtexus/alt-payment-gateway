package com.example.payment_service.notification.sms;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class HttpSmsSenderTests {

	private HttpServer server;

	private final AtomicReference<String> method = new AtomicReference<>();

	private final AtomicReference<String> authorization = new AtomicReference<>();

	private final AtomicReference<String> contentType = new AtomicReference<>();

	private final AtomicReference<String> body = new AtomicReference<>();

	private volatile int responseStatus = 202;

	private volatile Duration responseDelay = Duration.ZERO;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/send", (exchange) -> {
			method.set(exchange.getRequestMethod());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			try {
				Thread.sleep(responseDelay);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(responseStatus, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void postsPhoneAndMessageAsJsonWithBearerKey() {
		sender("secret", Duration.ofSeconds(5)).send("+380501112233", "Payment 49.90 EUR succeeded");

		assertThat(method).hasValue("POST");
		assertThat(authorization).hasValue("Bearer secret");
		assertThat(contentType.get()).startsWith("application/json");
		assertThat(body.get()).contains("\"to\":\"+380501112233\"")
			.contains("\"message\":\"Payment 49.90 EUR succeeded\"");
	}

	@Test
	void omitsAuthorizationWithoutApiKey() {
		sender(null, Duration.ofSeconds(5)).send("+380501112233", "Hi");

		assertThat(authorization).hasValue(null);
	}

	@Test
	void serverErrorIsThrown() {
		responseStatus = 500;

		assertThatExceptionOfType(HttpServerErrorException.class)
			.isThrownBy(() -> sender("secret", Duration.ofSeconds(5)).send("+380501112233", "Hi"));
	}

	@Test
	void slowServerTimesOut() {
		responseDelay = Duration.ofSeconds(2);

		assertThatExceptionOfType(ResourceAccessException.class)
			.isThrownBy(() -> sender("secret", Duration.ofMillis(200)).send("+380501112233", "Hi"));
	}

	private HttpSmsSender sender(String apiKey, Duration timeout) {
		URI url = URI.create("http://localhost:" + server.getAddress().getPort() + "/send");
		return new HttpSmsSender(new SmsProperties(SmsProperties.Provider.HTTP,
				new SmsProperties.Http(url, apiKey, timeout)));
	}

}
