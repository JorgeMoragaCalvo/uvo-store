package org.uvo.uvostore.service.pos;

public interface PosSyncService {
    // Ports SyncProductController::syncProduct() — create-or-update a Product from an inbound
    // UvoPOS sync call, keyed by (externalId, companyId) via ProductSyncMapping.
    SyncProductResult syncProduct(SyncProductCommand command);
}
