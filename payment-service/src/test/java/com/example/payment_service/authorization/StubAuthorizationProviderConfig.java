package com.example.payment_service.authorization;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.example.payment_service.provider.ActionStatusRequest;
import com.example.payment_service.provider.AuthorizationResult;
import com.example.payment_service.provider.AuthorizationStatusCheckingProvider;
import com.example.payment_service.provider.CaptureRequest;
import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.ProviderRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.ProviderStatusRequest;
import com.example.payment_service.provider.VoidRequest;

@TestConfiguration(proxyBeanMethods = false)
class StubAuthorizationProviderConfig {

	@Bean
	StubAuthorizationProvider stubAuthorizationProvider() {
		return new StubAuthorizationProvider();
	}

	static class StubAuthorizationProvider implements PaymentProvider, AuthorizationStatusCheckingProvider {

		static final String NAME = "stub-auth";

		private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

		volatile Function<ProviderRequest, AuthorizationResult> authorize;

		volatile Supplier<ProviderResult> capture;

		volatile Supplier<ProviderResult> voidAnswer;

		volatile Supplier<AuthorizationResult> authorizationStatus;

		volatile Supplier<ProviderResult> actionStatus;

		StubAuthorizationProvider() {
			reset();
		}

		void reset() {
			calls.clear();
			authorize = (request) -> AuthorizationResult.approved("stub_a_" + request.paymentId(),
					Instant.now().plus(7, ChronoUnit.DAYS));
			capture = () -> ProviderResult.approved("stub_c");
			voidAnswer = () -> ProviderResult.approved("stub_v");
			authorizationStatus = () -> AuthorizationResult.pending("stub_a");
			actionStatus = () -> ProviderResult.pending("stub_x");
		}

		int calls(String operation) {
			AtomicInteger count = calls.get(operation);
			return (count != null) ? count.get() : 0;
		}

		private void count(String operation) {
			calls.computeIfAbsent(operation, (key) -> new AtomicInteger()).incrementAndGet();
		}

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public ProviderResult process(ProviderRequest request) {
			return ProviderResult.approved("stub_p");
		}

		@Override
		public boolean checksStatus() {
			return true;
		}

		@Override
		public ProviderResult checkStatus(ProviderStatusRequest request) {
			count("checkStatus");
			return ProviderResult.pending("stub_p");
		}

		@Override
		public boolean supportsManualCapture() {
			return true;
		}

		@Override
		public AuthorizationResult authorize(ProviderRequest request) {
			count("authorize");
			return authorize.apply(request);
		}

		@Override
		public ProviderResult capture(CaptureRequest request) {
			count("capture");
			return capture.get();
		}

		@Override
		public ProviderResult voidAuthorization(VoidRequest request) {
			count("void");
			return voidAnswer.get();
		}

		@Override
		public AuthorizationResult checkAuthorizationStatus(ProviderStatusRequest request) {
			count("authorizationStatus");
			return authorizationStatus.get();
		}

		@Override
		public ProviderResult checkActionStatus(ActionStatusRequest request) {
			count("actionStatus:" + request.action());
			return actionStatus.get();
		}

	}

}
