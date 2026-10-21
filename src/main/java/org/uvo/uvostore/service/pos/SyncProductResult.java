package org.uvo.uvostore.service.pos;

// POS-3: applied = false cuando la entrada se descartó (vieja, repetida o con el mapeo inactivo); message dice
// por qué. logId es la fila de sync_webhook_logs que la registra.
public record SyncProductResult(Long productId, boolean created, boolean applied, String message, Long logId) {
}
