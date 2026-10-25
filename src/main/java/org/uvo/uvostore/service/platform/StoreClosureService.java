package org.uvo.uvostore.service.platform;

/** PROD-23. Cerrar una tienda: el final de su ciclo de vida desde la plataforma. */
public interface StoreClosureService {

    /**
     * SUSPENDED → CLOSED, que es terminal. Exige el nick escrito y que no quede nada en vuelo. Cerrar una tienda ya
     * cerrada (con el nick correcto) la deja igual.
     */
    PlatformStoreDto close(Long storeId, String confirmSlug, StoreStatusReason reason);
}
