package org.uvo.uvostore.service.outbox;

/**
 * PROD-13b. Un correo listo para enviar, en valores planos para que cruce la frontera de la transacción de
 * lectura sin arrastrar nada perezoso. O el motivo por el que ya no hay que enviarlo.
 */
public record EmailDraft(String to, String subject, String body, String skipReason) {

    public static EmailDraft of(String to, String subject, String body) {
        return new EmailDraft(to, subject, body, null);
    }

    public static EmailDraft skip(String reason) {
        return new EmailDraft(null, null, null, reason);
    }

    public boolean skipped() {
        return skipReason != null;
    }
}
