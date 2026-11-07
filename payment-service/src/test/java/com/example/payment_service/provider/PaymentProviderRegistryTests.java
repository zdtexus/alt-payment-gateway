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

	@Test
	void listsOnlyProvidersThatCanCheckStatusNow() {
		StatusProvider checking = new StatusProvider("checking", true);
		StatusProvider switchedOff = new StatusProvider("switched-off", false);

		PaymentProviderRegistry registry = new PaymentProviderRegistry(
				List.of(new NamedProvider("plain"), switchedOff, checking));

		assertThat(registry.statusCheckingProviders()).containsOnlyKeys("checking").containsEntry("checking", checking);
	}

	@Test
	void findsOnlyProvidersThatCanRefundNow() {
		RefundProvider refunding = new RefundProvider("refunding", true);
		RefundProvider switchedOff = new RefundProvider("switched-off", false);

		PaymentProviderRegistry registry = new PaymentProviderRegistry(
				List.of(new NamedProvider("plain"), switchedOff, refunding));

		assertThat(registry.findRefundingProvider("refunding")).containsSame(refunding);
		assertThat(registry.findRefundingProvider("switched-off")).isEmpty();
		assertThat(registry.findRefundingProvider("plain")).isEmpty();
		assertThat(registry.findRefundingProvider("unknown")).isEmpty();
	}

	@Test
	void listsOnlyProvidersThatCanCheckRefundStatusNow() {
		RefundStatusProvider checking = new RefundStatusProvider("refund-checking", true);
		RefundStatusProvider switchedOff = new RefundStatusProvider("refund-switched-off", false);

		PaymentProviderRegistry registry = new PaymentProviderRegistry(
				List.of(new StatusProvider("payments-only", true), switchedOff, checking));

		assertThat(registry.refundStatusCheckingProviders()).containsOnlyKeys("refund-checking");
		assertThat(registry.statusCheckingProviders()).containsOnlyKeys("payments-only", "refund-checking");
	}

	@Test
	void findsOnlyProvidersThatCanAuthorizeNow() {
		AuthorizationProvider authorizing = new AuthorizationProvider("authorizing", true);
		AuthorizationProvider switchedOff = new AuthorizationProvider("switched-off", false);

		PaymentProviderRegistry registry = new PaymentProviderRegistry(
				List.of(new NamedProvider("plain"), switchedOff, authorizing));

		assertThat(registry.findAuthorizingProvider("authorizing")).containsSame(authorizing);
		assertThat(registry.findAuthorizingProvider("switched-off")).isEmpty();
		assertThat(registry.findAuthorizingProvider("plain")).isEmpty();
		assertThat(registry.findAuthorizingProvider("unknown")).isEmpty();
	}

	record AuthorizationProvider(String name, boolean supportsManualCapture)
			implements PaymentProvider, AuthorizingProvider {

		@Override
		public ProviderResult process(ProviderRequest request) {
			return ProviderResult.approved();
		}

		@Override
		public AuthorizationResult authorize(ProviderRequest request) {
			return AuthorizationResult.approved(null, java.time.Instant.now());
		}

		@Override
		public ProviderResult capture(CaptureRequest request) {
			return ProviderResult.approved();
		}

		@Override
		public ProviderResult voidAuthorization(VoidRequest request) {
			return ProviderResult.approved();
		}

	}

	record RefundStatusProvider(String name, boolean checksStatus)
			implements PaymentProvider, RefundStatusCheckingProvider {

		@Override
		public ProviderResult process(ProviderRequest request) {
			return ProviderResult.approved();
		}

		@Override
		public ProviderResult checkStatus(ProviderStatusRequest request) {
			return ProviderResult.approved();
		}

		@Override
		public ProviderResult checkRefundStatus(RefundStatusRequest request) {
			return ProviderResult.approved();
		}

	}

	record RefundProvider(String name, boolean supportsRefunds) implements PaymentProvider, RefundingProvider {

		@Override
		public ProviderResult process(ProviderRequest request) {
			return ProviderResult.approved();
		}

		@Override
		public ProviderResult refund(RefundRequest request) {
			return ProviderResult.approved();
		}

	}

	record StatusProvider(String name, boolean checksStatus) implements PaymentProvider, StatusCheckingProvider {

		@Override
		public ProviderResult process(ProviderRequest request) {
			return ProviderResult.approved();
		}

		@Override
		public ProviderResult checkStatus(ProviderStatusRequest request) {
			return ProviderResult.approved();
		}

	}

	record NamedProvider(String name) implements PaymentProvider {

		@Override
		public ProviderResult process(ProviderRequest request) {
			return ProviderResult.approved();
		}

	}

}
