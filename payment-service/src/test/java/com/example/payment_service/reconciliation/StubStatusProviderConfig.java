package com.example.payment_service.reconciliation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.example.payment_service.provider.PaymentProvider;
import com.example.payment_service.provider.ProviderRequest;
import com.example.payment_service.provider.ProviderResult;
import com.example.payment_service.provider.ProviderStatusRequest;
import com.example.payment_service.provider.StatusCheckingProvider;

@TestConfiguration(proxyBeanMethods = false)
class StubStatusProviderConfig {

	@Bean
	StubStatusProvider stubStatusProvider() {
		return new StubStatusProvider();
	}

	static class StubStatusProvider implements PaymentProvider, StatusCheckingProvider {

		static final String NAME = "stub-status";

		private final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();

		private volatile Function<ProviderStatusRequest, ProviderResult> answer = (request) -> ProviderResult
			.approved(request.providerReference());

		private volatile Consumer<ProviderStatusRequest> beforeAnswer = (request) -> {
		};

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public ProviderResult process(ProviderRequest request) {
			return ProviderResult.pending("stub-" + request.paymentId());
		}

		@Override
		public boolean checksStatus() {
			return true;
		}

		@Override
		public ProviderResult checkStatus(ProviderStatusRequest request) {
			calls.computeIfAbsent(request.paymentId(), (id) -> new AtomicInteger()).incrementAndGet();
			beforeAnswer.accept(request);
			return answer.apply(request);
		}

		void answer(Function<ProviderStatusRequest, ProviderResult> answer) {
			this.answer = answer;
		}

		void beforeAnswer(Consumer<ProviderStatusRequest> beforeAnswer) {
			this.beforeAnswer = beforeAnswer;
		}

		int calls(UUID paymentId) {
			AtomicInteger count = calls.get(paymentId);
			return (count != null) ? count.get() : 0;
		}

		Map<UUID, AtomicInteger> allCalls() {
			return calls;
		}

		void reset() {
			calls.clear();
			answer = (request) -> ProviderResult.approved(request.providerReference());
			beforeAnswer = (request) -> {
			};
		}

	}

}
