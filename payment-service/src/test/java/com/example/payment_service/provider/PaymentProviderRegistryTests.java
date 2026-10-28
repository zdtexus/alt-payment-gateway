package com.example.payment_service.provider;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PaymentProviderRegistryTests {

	@Test
	void findsSeveralProvidersByName() {
		PaymentProvider first = new NamedProvider("first");
		PaymentProvider second = new NamedProvider("second");

		PaymentProviderRegistry registry = new PaymentProviderRegistry(List.of(second, first));

		assertThat(registry.find("first")).containsSame(first);
		assertThat(registry.find("second")).containsSame(second);
		assertThat(registry.find("unknown")).isEmpty();
		assertThat(registry.names()).containsExactly("first", "second");
	}

	@Test
	void rejectsTwoProvidersWithTheSameName() {
		assertThatIllegalStateException()
			.isThrownBy(() -> new PaymentProviderRegistry(List.of(new NamedProvider("same"), new NamedProvider("same"))))
			.withMessageStartingWith("Two payment providers are named 'same'");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", " " })
	void rejectsProviderWithoutName(String name) {
		assertThatIllegalStateException()
			.isThrownBy(() -> new PaymentProviderRegistry(List.of(new NamedProvider(name))))
			.withMessageEndingWith("must have a non-blank name()");
	}

	@Test
	void rejectsNoProviders() {
		assertThatIllegalStateException().isThrownBy(() -> new PaymentProviderRegistry(List.of()))
			.withMessage("No payment provider is available");
	}

	record NamedProvider(String name) implements PaymentProvider {

		@Override
		public ProviderResult process(ProviderRequest request) {
			return ProviderResult.approved();
		}

	}

}
