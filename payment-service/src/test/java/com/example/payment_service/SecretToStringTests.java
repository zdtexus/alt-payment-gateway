package com.example.payment_service;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

import com.example.payment_service.auth.AdminProperties;
import com.example.payment_service.dto.ApiKeyResponse;
import com.example.payment_service.dto.CreatedMerchantResponse;
import com.example.payment_service.notification.sms.SmsProperties;
import com.example.payment_service.provider.HttpPaymentProviderProperties;

import static org.assertj.core.api.Assertions.assertThat;

class SecretToStringTests {

	private static final List<String> SECRET_WORDS = List.of("key", "secret", "password", "token");

	@Test
	void findsTheRecordsKnownToHoldSecrets() {
		assertThat(recordsHoldingSecrets()).contains(AdminProperties.class, ApiKeyResponse.class,
				CreatedMerchantResponse.class, SmsProperties.Http.class, SmsProperties.class,
				HttpPaymentProviderProperties.class);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("recordsHoldingSecrets")
	void toStringDoesNotRevealSecrets(Class<?> type) throws ReflectiveOperationException {
		List<String> samples = new ArrayList<>();

		String text = instantiate(type, samples).toString();

		assertThat(samples).isNotEmpty();
		assertThat(text).as("%s.toString()", type.getName()).doesNotContain(samples);
	}

	static List<Class<?>> recordsHoldingSecrets() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter((reader, factory) -> Record.class.getName()
			.equals(reader.getClassMetadata().getSuperClassName()));
		return scanner.findCandidateComponents(SecretToStringTests.class.getPackageName())
			.stream()
			.map(BeanDefinition::getBeanClassName)
			.<Class<?>>map((name) -> ClassUtils.resolveClassName(name, SecretToStringTests.class.getClassLoader()))
			.filter(SecretToStringTests::isMainCode)
			.filter(SecretToStringTests::holdsSecret)
			.toList();
	}

	private static boolean isMainCode(Class<?> type) {
		return !type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes");
	}

	private static boolean holdsSecret(Class<?> type) {
		return type.isRecord() && Arrays.stream(type.getRecordComponents())
			.anyMatch((component) -> isSecret(component) || holdsSecret(component.getType()));
	}

	private static boolean isSecret(RecordComponent component) {
		String name = component.getName().toLowerCase(Locale.ROOT);
		return component.getType() == String.class && SECRET_WORDS.stream().anyMatch(name::contains);
	}

	private static Object instantiate(Class<?> type, List<String> samples) throws ReflectiveOperationException {
		RecordComponent[] components = type.getRecordComponents();
		Object[] values = new Object[components.length];
		for (int i = 0; i < components.length; i++) {
			values[i] = valueFor(type, components[i], samples);
		}
		Constructor<?> constructor = type.getDeclaredConstructor(
				Stream.of(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
		constructor.setAccessible(true);
		return constructor.newInstance(values);
	}

	private static Object valueFor(Class<?> owner, RecordComponent component, List<String> samples)
			throws ReflectiveOperationException {
		Class<?> type = component.getType();
		if (isSecret(component)) {
			String sample = "sample-" + owner.getSimpleName() + "-" + component.getName() + "-0123456789";
			samples.add(sample);
			return sample;
		}
		if (type.isRecord()) {
			return instantiate(type, samples);
		}
		if (type.isEnum()) {
			return type.getEnumConstants()[0];
		}
		return Map.<Class<?>, Object>of(String.class, "value", UUID.class,
				UUID.fromString("7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b"), URI.class, URI.create("https://example.com/x"),
				Duration.class, Duration.ofSeconds(1), Instant.class, Instant.EPOCH, BigDecimal.class, BigDecimal.ONE,
				Set.class, Set.of(), List.class, List.of(), boolean.class, false, int.class, 0)
			.get(type);
	}

}
