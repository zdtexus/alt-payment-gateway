package com.example.payment_service.notification.sms;

public interface SmsSender {

	void send(String phone, String message);

}
