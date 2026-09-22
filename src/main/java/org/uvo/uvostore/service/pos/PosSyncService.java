package org.uvo.uvostore.service.pos;

import org.uvo.uvostore.entity.pos.PosConnection;

public interface PosSyncService {
    // Ports SyncProductController::syncProduct() — create-or-update a Product from an inbound
    // UvoPOS sync call, keyed by (externalId, companyId) via ProductSyncMapping.
    //
    // F01: la conexión va en la firma, y es la autenticada por PosApiKeyAuthFilter. De ella salen el
    // companyId del mapping y la tienda donde se escribe; el comando solo trae datos del producto.
    SyncProductResult syncProduct(PosConnection connection, SyncProductCommand command);
}
