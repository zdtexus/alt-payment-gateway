package com.example.payment_service.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "payment.reconciliation.enabled", matchIfMissing = true)
public class ReconciliationSchedulingConfig implements SchedulingConfigurer {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationSchedulingConfig.class);

	private final PendingPaymentReconciler reconciler;

	private final ReconciliationProperties properties;

	public ReconciliationSchedulingConfig(PendingPaymentReconciler reconciler, ReconciliationProperties properties) {
		this.reconciler = reconciler;
		this.properties = properties;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addFixedDelayTask(
				new FixedDelayTask(reconciler::runScheduled, properties.interval(), properties.interval()));
		log.info("Pending payments are checked with their providers every {} (first check {} after creation, "
				+ "backoff up to {}, alert after {})", properties.interval(), properties.minAge(),
				properties.maxBackoff(), properties.alertAfter());
	}

}
