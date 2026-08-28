package org.uvo.uvostore.service.notification;

public interface EmailService {

    void send(String to, String subject, String body);
}
