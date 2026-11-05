package com.example.payment_service.notification.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "notification.sms.provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements SmsSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

	private static final int VISIBLE_DIGITS = 4;

	@Override
	public void send(String phone, String message) {
		log.info("SMS to {}: {}", mask(phone), message);
	}

	static String mask(String phone) {
		if (phone == null || phone.length() <= VISIBLE_DIGITS) {
			return "***";
		}
		return "***" + phone.substring(phone.length() - VISIBLE_DIGITS);
	}

}
