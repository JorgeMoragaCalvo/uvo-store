package org.uvo.uvostore.service.notification;

public interface EmailService {

    /**
     * Envía y no falla nunca: sin SMTP configurado lo omite, y un error de envío lo registra y lo traga.
     * Para los llamadores síncronos (recuperación de contraseña, invitación de administradores), que no
     * deben romperse porque el correo no esté disponible.
     */
    void send(String to, String subject, String body);

    /**
     * PROD-13b. Envía y dice qué pasó. Para el outbox, que necesita saber si el correo salió para decidir
     * si lo reintenta: lanza {@link org.springframework.mail.MailException} si el envío falla, y devuelve
     * {@link Delivery#NOT_CONFIGURED} si no hay SMTP con el que intentarlo.
     */
    Delivery deliver(String to, String subject, String body);

    enum Delivery {
        SENT,
        /** No hay SMTP configurado: no se intentó nada. */
        NOT_CONFIGURED
    }
}
