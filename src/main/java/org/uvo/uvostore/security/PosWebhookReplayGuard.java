package org.uvo.uvostore.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.repository.PosWebhookReceiptRepository;

import java.time.Duration;
import java.time.Instant;

/**
 * POS-3 e IMP-08. Los nonces del POS ya vistos, en la base y no en memoria (contrato §3, regla 3).
 *
 * <p>Con la tabla ({@code UNIQUE (key_id, nonce)}) el guarda sobrevive a un reinicio y vale igual con varias
 * instancias. Cubre todo lo que entra firmado por el POS: los webhooks y {@code /api/sync/product}.
 *
 * <p>El registro confirma en el acto, en su propia transacción, aunque la petición falle después. No estorba al POS:
 * cada reintento suyo lleva un nonce nuevo (§5, «Entrega»).
 */
@Component
public class PosWebhookReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(PosWebhookReplayGuard.class);

    /**
     * Cuánto se recuerda un nonce: <b>dos</b> ventanas, más un margen (§3 pide al menos el doble).
     *
     * <p>La ventana acepta un timestamp hasta 300 s por detrás <i>o por delante</i> del reloj, así que una petición con
     * el timestamp adelantado sigue fresca hasta 600 s después de recibida.
     */
    static final Duration RETENTION = Duration.ofSeconds(2 * UvoHmac.WINDOW_SECONDS + 60);

    private final PosWebhookReceiptRepository repository;

    public PosWebhookReplayGuard(PosWebhookReceiptRepository repository) {
        this.repository = repository;
    }

    /**
     * Registra el nonce y dice si es la primera vez que llega con esa llave. Solo se llama con la firma <b>ya
     * verificada</b>: recordar antes dejaría que cualquiera quemara nonces ajenos sin conocer el secreto.
     */
    public boolean firstTime(String keyId, String nonce) {
        return repository.recordIfNew(keyId, nonce) == 1;
    }

    /**
     * Borra lo que ya no puede volver a ser fresco: el control de timestamp lo rechazaría antes de llegar aquí.
     * Sin advisory lock: dos instancias borrando a la vez no se estorban.
     */
    @Scheduled(fixedDelayString = "${app.pos.webhook-receipts.purge-interval-ms:600000}",
            initialDelayString = "${app.pos.webhook-receipts.purge-interval-ms:600000}")
    public void purgeExpired() {
        int purged = repository.deleteReceivedBefore(Instant.now().minus(RETENTION));
        if (purged > 0) {
            log.debug("Purgados {} nonces del POS", purged);
        }
    }
}
