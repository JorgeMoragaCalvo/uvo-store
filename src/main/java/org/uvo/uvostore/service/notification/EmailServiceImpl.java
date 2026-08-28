package org.uvo.uvostore.service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

// One shared SMTP relay for the whole platform (same criterion as Stripe/S3: shared
// infrastructure, not something each store configures) — see spring.mail.* in
// application.properties. Same graceful-degradation pattern as every other external
// integration in this project (Chilexpress/Webpay/MercadoPago): if it isn't configured, log and
// skip instead of throwing, so a caller like checkout or password reset never breaks because mail
// isn't set up.
@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    private final JavaMailSender mailSender;
    private final String mailHost;
    private final String fromAddress;

    public EmailServiceImpl(JavaMailSender mailSender,
                             @Value("${spring.mail.host:}") String mailHost,
                             @Value("${app.mail.from:no-reply@uvostore.cl}") String fromAddress) {
        this.mailSender = mailSender;
        this.mailHost = mailHost;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String to, String subject, String body) {
        if (mailHost == null || mailHost.isBlank()) {
            log.warn("Correo no enviado (SMTP no configurado) to={} subject={}", to, subject);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Correo enviado to={} subject={}", to, subject);
        } catch (Exception e) {
            log.error("Error enviando correo to={} subject={} error={}", to, subject, e.getMessage());
        }
    }
}
