package org.uvo.uvostore.service.pos;

// POS-3: applied = false cuando la entrada no se aplicó (vieja, repetida o con el mapeo inactivo); message dice
// por qué. logId es la fila de sync_webhook_logs que la registra. IMP-08: ignored = descartada por la versión (§5).
public record SyncProductResult(Long productId, boolean created, boolean applied, String message, Long logId,
                                boolean ignored) {

    public SyncProductResult(Long productId, boolean created, boolean applied, String message, Long logId) {
        this(productId, created, applied, message, logId, false);
    }
}
