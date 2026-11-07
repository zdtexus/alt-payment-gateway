package com.example.payment_service.error;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.example.payment_service.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.datasource.hikari.connection-timeout=1000")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class DatabaseOutageIntegrationTests {

	private static final String DEMO_KEY = "demo-key-123";

	private static final String ADMIN_KEY = "test-admin-key-0123456789";

	private final HttpClient client = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private PostgreSQLContainer postgres;

	@Test
	void stoppedDatabaseGives503OnKeyCheckAndInController(CapturedOutput output) throws Exception {
		assertThat(send(listPayments()).statusCode()).isEqualTo(200);

		postgres.stop();

		HttpResponse<String> keyCheck = send(listPayments());
		assertDatabaseUnavailable(keyCheck, "/api/v1/payments");

		String merchantPath = "/admin/v1/merchants/" + UUID.randomUUID();
		HttpResponse<String> controller = send(request(merchantPath).header("X-Admin-Key", ADMIN_KEY)
			.header("Content-Type", "application/json")
			.method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"SUSPENDED\"}")));
		assertDatabaseUnavailable(controller, merchantPath);

		assertThat(output).contains("Database unavailable on GET /api/v1/payments")
			.contains("Database unavailable on PATCH " + merchantPath);
	}

	private void assertDatabaseUnavailable(HttpResponse<String> response, String path) {
		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(response.headers().firstValue("Retry-After")).hasValue("5");
		Map<String, Object> body = JsonPath.read(response.body(), "$");
		assertThat(body).containsOnlyKeys("timestamp", "status", "error", "message", "path")
			.containsEntry("status", 503)
			.containsEntry("error", "SERVICE_UNAVAILABLE")
			.containsEntry("message", "Database is unavailable")
			.containsEntry("path", path);
		assertThat(response.body()).doesNotContain("jdbc:")
			.doesNotContain("Hikari")
			.doesNotContain("Exception");
	}

	private HttpRequest.Builder listPayments() {
		return request("/api/v1/payments").header("X-API-Key", DEMO_KEY).GET();
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("Accept", "application/json");
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

}
