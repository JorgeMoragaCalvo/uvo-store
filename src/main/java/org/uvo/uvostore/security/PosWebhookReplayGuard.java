package org.uvo.uvostore.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.repository.PosWebhookReceiptRepository;

import java.time.Duration;
import java.time.Instant;

/**
 * POS-3. Los webhooks del POS ya atendidos, en la base y no en memoria.
 *
 * <p>F21 los guardaba en una caché Caffeine dentro de {@link PosWebhookAuthFilter}, con dos límites escritos:
 * un reinicio dentro de la ventana de frescura perdía el guarda, y con más de una instancia cada una recordaba
 * lo suyo. Con la tabla ({@code UNIQUE (company_id, signature)}) las dos cosas dejan de pasar.
 *
 * <p>El registro confirma en el acto, en su propia transacción, aunque la petición falle después. Es lo mismo
 * que hacía la caché, y no estorba al POS: un reintento suyo lleva otro timestamp y, por tanto, otra firma.
 */
@Component
public class PosWebhookReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(PosWebhookReplayGuard.class);

    /**
     * Cuánto se recuerda un webhook: <b>dos</b> ventanas de frescura, más un margen.
     *
     * <p>La ventana acepta un timestamp hasta 300 s por detrás <i>o por delante</i> del reloj, así que una firma
     * con el timestamp adelantado sigue fresca hasta 600 s después de recibida. La caché de F21 caducaba a los
     * 310 s, y entre medias la misma petición se podía repetir.
     */
    static final Duration RETENTION = Duration.ofSeconds(2 * PosWebhookAuthFilter.FRESHNESS_WINDOW_SECONDS + 60);

    private final PosWebhookReceiptRepository repository;

    public PosWebhookReplayGuard(PosWebhookReceiptRepository repository) {
        this.repository = repository;
    }

    /**
     * Registra el webhook y dice si es la primera vez que llega. Solo se llama con la firma <b>ya verificada</b>
     * (ver el filtro).
     */
    public boolean firstTime(long companyId, String signature) {
        return repository.recordIfNew(companyId, signature) == 1;
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
            log.debug("Purgados {} recibos de webhooks del POS", purged);
        }
    }
}
