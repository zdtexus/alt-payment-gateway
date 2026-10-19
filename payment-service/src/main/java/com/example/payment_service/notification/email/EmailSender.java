package com.example.payment_service.notification.email;

public interface EmailSender {

	void send(String to, String subject, String body);

}
