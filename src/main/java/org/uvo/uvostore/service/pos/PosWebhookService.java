package org.uvo.uvostore.service.pos;

import org.uvo.uvostore.entity.pos.SyncWebhookLog;

// Ports PosWebhookController + the ProcessStockUpdate/ProcessProductUpdate queued jobs it
// dispatches. There's no job-queue infrastructure built yet in this Spring project, so these
// run synchronously within the webhook request instead of being queued — same end effect
// (SyncWebhookLog row + product mutation), just not backgrounded. Revisit if webhook volume
// ever makes that request-time cost a problem.
public interface PosWebhookService {
    SyncWebhookLog handleStockUpdated(StockUpdatePayload payload);
    SyncWebhookLog handleProductUpdated(ProductUpdatePayload payload);
    SyncWebhookLog handleStockAlert(StockAlertPayload payload);
    SyncWebhookLog handleProductCreated(ProductCreatedPayload payload);
}
