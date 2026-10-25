package org.uvo.uvostore.service.platform;

/** PROD-23. Suspender y reactivar una tienda, y devolverle el acceso a su dueño. El cierre es {@link StoreClosureService}. */
public interface StoreLifecycleService {

    /** ACTIVE → SUSPENDED. Una tienda ya suspendida queda igual (un reintento del POS no falla); una cerrada, 400. */
    PlatformStoreDto suspend(Long storeId, StoreStatusReason reason);

    /** SUSPENDED → ACTIVE. Una tienda ya activa queda igual; una cerrada, 400. */
    PlatformStoreDto reactivate(Long storeId);

    /**
     * Una invitación nueva para el dueño, que deja su cuenta como recién creada: activa, sin contraseña, sin sesiones,
     * con el rol de dueño y, si se pide, con otro correo. El correo sale después del commit.
     *
     * @param newEmail nulo o vacío = el mismo
     */
    PlatformStoreDto recoverOwnerAccess(Long storeId, String newEmail);
}
