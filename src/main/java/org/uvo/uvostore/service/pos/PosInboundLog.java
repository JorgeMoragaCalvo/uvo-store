package org.uvo.uvostore.service.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;
import org.uvo.uvostore.repository.SyncWebHookLogRepository;

import java.time.Instant;
import java.util.Map;

/**
 * POS-3. El registro de lo que entra desde el POS ({@code sync_webhook_logs}), compartido por los webhooks y
 * la sincronización. Antes vivía dentro de {@code PosWebhookServiceImpl} y la sincronización no dejaba rastro.
 */
@Component
public class PosInboundLog {

    private static final Logger LOG = LoggerFactory.getLogger(PosInboundLog.class);

    private final SyncWebHookLogRepository repository;

    public PosInboundLog(SyncWebHookLogRepository repository) {
        this.repository = repository;
    }

    public SyncWebhookLog open(Long companyId, String eventType, Map<String, Object> payload) {
        SyncWebhookLog log = new SyncWebhookLog();
        log.setCompanyId(companyId);
        log.setEventType(eventType);
        log.setStatus(WebhookStatus.RECEIVED);
        log.setPayload(payload);
        return repository.save(log);
    }

    public SyncWebhookLog success(SyncWebhookLog log) {
        return close(log, WebhookStatus.SUCCESS, null);
    }

    public SyncWebhookLog failed(SyncWebhookLog log, String error) {
        return close(log, WebhookStatus.FAILED, error);
    }

    /**
     * Una entrada vieja o repetida que no se aplicó a propósito. Sin Sentry: con un POS que entrega fuera de
     * orden o reintenta es lo esperable, y una alerta por cada una enterraría las que importan.
     */
    public SyncWebhookLog discarded(SyncWebhookLog log, String reason) {
        LOG.info("Entrada del POS descartada company_id={} event={} motivo={}", log.getCompanyId(),
                log.getEventType(), reason);
        return close(log, WebhookStatus.DISCARDED, reason);
    }

    private SyncWebhookLog close(SyncWebhookLog log, WebhookStatus status, String error) {
        log.setStatus(status);
        log.setErrorMessage(error);
        log.setProcessedAt(Instant.now());
        return repository.save(log);
    }
}
