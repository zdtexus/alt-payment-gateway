package com.example.payment_service.error;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import com.example.payment_service.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, ContainerErrorFormatTests.FailingFilterConfig.class })
class ContainerErrorFormatTests {

	private static final String DEMO_KEY = "demo-key-123";

	private static final String FAILING_PATH = "/api/v1/failing-filter";

	private static final String INTERNAL_DETAIL = "password=hunter2 at jdbc:postgresql://db/payments";

	private final HttpClient client = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private ErrorAttributesCheck errorAttributesCheck;

	@Test
	void unknownPathUsesCommonFormat() throws Exception {
		HttpResponse<String> response = send(request("/api/v1/no-such-endpoint").GET());

		assertCommonError(response, 404, "NOT_FOUND", "Not Found", "/api/v1/no-such-endpoint");
	}

	@Test
	void wrongMethodUsesCommonFormatAndKeepsAllowHeader() throws Exception {
		HttpResponse<String> response = send(request("/api/v1/payments").DELETE());

		assertCommonError(response, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed", "/api/v1/payments");
		assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(
				(allow) -> assertThat(allow).contains("GET").contains("POST"));
	}

	@Test
	void unsupportedContentTypeUsesCommonFormat() throws Exception {
		HttpResponse<String> response = send(request("/api/v1/payments").header("Content-Type", "text/plain")
			.POST(HttpRequest.BodyPublishers.ofString("amount=1")));

		assertCommonError(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type", "/api/v1/payments");
	}

	@Test
	void exceptionInsideFilterUsesCommonFormatWithoutInternalDetails() throws Exception {
		HttpResponse<String> response = send(request(FAILING_PATH).GET());

		assertCommonError(response, 500, "INTERNAL_SERVER_ERROR", "Internal Server Error", FAILING_PATH);
		assertThat(response.body()).doesNotContain("hunter2")
			.doesNotContain("jdbc:")
			.doesNotContain("IllegalStateException")
			.doesNotContain("trace");
	}

	@Test
	void serviceReplacesSpringBootsDefaultErrorAttributes() {
		assertThat(errorAttributesCheck.type()).isEqualTo(ServiceErrorAttributes.class);
	}

	private void assertCommonError(HttpResponse<String> response, int status, String error, String message,
			String path) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("application/json"));
		Map<String, Object> body = JsonPath.read(response.body(), "$");
		assertThat(body).containsOnlyKeys("timestamp", "status", "error", "message", "path");
		assertThat(body).containsEntry("status", status)
			.containsEntry("error", error)
			.containsEntry("message", message)
			.containsEntry("path", path);
		assertThat((String) body.get("timestamp")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("X-API-Key", DEMO_KEY)
			.header("Accept", "application/json");
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	record ErrorAttributesCheck(Class<?> type) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FailingFilterConfig {

		@Bean
		FilterRegistrationBean<Filter> failingFilter() {
			FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
				throw new IllegalStateException(INTERNAL_DETAIL);
			});
			registration.addUrlPatterns(FAILING_PATH);
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
			return registration;
		}

		@Bean
		ErrorAttributesCheck errorAttributesCheck(
				org.springframework.boot.webmvc.error.ErrorAttributes errorAttributes) {
			return new ErrorAttributesCheck(errorAttributes.getClass());
		}

	}

}
