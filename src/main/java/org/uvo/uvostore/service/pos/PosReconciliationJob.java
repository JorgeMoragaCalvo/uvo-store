package org.uvo.uvostore.service.pos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.service.AdvisoryLock;

/**
 * POS-4. La conciliación con UvoPOS, cada {@code app.pos.reconciliation.interval-ms} (15 minutos por defecto: es
 * el plazo en que se detecta una divergencia). La lógica está en {@link PosReconciliationService}, que también
 * usa la resincronización del panel.
 *
 * <p><b>Activado por defecto</b>, como la conciliación de pagos: sin la integración encendida (de plataforma y de
 * tienda) no hace nada, y con ella lo único que escribe es el stock y el catálogo que el POS ya tiene.
 */
@Component
@ConditionalOnProperty(name = "app.pos.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class PosReconciliationJob {

    /** La siguiente a las del outbox ({@code OutboxDispatcher}): solo tiene que no repetirse. */
    private static final long ADVISORY_LOCK_KEY = 7_312_025_005L;

    private final PosReconciliationService reconciliationService;
    private final PosIntegrationPolicy posPolicy;
    private final AdvisoryLock advisoryLock;

    public PosReconciliationJob(PosReconciliationService reconciliationService, PosIntegrationPolicy posPolicy,
                                AdvisoryLock advisoryLock) {
        this.reconciliationService = reconciliationService;
        this.posPolicy = posPolicy;
        this.advisoryLock = advisoryLock;
    }

    @Scheduled(fixedDelayString = "${app.pos.reconciliation.interval-ms:900000}",
            initialDelayString = "${app.pos.reconciliation.interval-ms:900000}")
    public void reconcile() {
        // Con la plataforma apagada ni siquiera se pide el cerrojo: no habría ninguna tienda que conciliar.
        if (!posPolicy.isEnabled()) {
            return;
        }
        advisoryLock.runExclusively(ADVISORY_LOCK_KEY, "conciliación con UvoPOS",
                reconciliationService::reconcileAll);
    }
}
