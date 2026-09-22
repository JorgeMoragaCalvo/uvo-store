package org.uvo.uvostore.security;

import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.entity.tenant.Store;

// Per-request current Store, resolved by TenantResolutionFilter from the request's subdomain.
// ThreadLocal is safe here because Tomcat serves requests thread-per-request by default; the
// filter clears it in a finally block so pooled threads never leak a previous request's tenant.
public final class TenantContext {

    private static final ThreadLocal<Store> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Store store) {
        CURRENT.set(store);
    }

    public static Store get() {
        return CURRENT.get();
    }

    public static Long currentStoreId() {
        Store store = CURRENT.get();
        return store == null ? null : store.getId();
    }

    // Every store-scoped endpoint needs a resolved tenant to do anything meaningful — there's
    // no legitimate "no store" case for them (only auth-less infra routes like /health skip this).
    public static Store requireCurrent() {
        Store store = CURRENT.get();
        if (store == null) {
            throw new BusinessException("No se pudo determinar la tienda para esta solicitud");
        }
        return store;
    }

    public static Long requireStoreId() {
        return requireCurrent().getId();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Ejecuta algo en nombre de una tienda concreta, fuera de una petición HTTP. Lo necesitan los
     * trabajos programados (G1, G2): no tienen petición y por tanto no pasan por
     * TenantResolutionFilter, pero sí llaman a código que exige tenant — {@code WebpayServiceImpl} y
     * {@code MercadoPagoServiceImpl} llaman a {@link #requireStoreId()} para leer las credenciales
     * de la tienda.
     *
     * <p>Existe como helper y no suelto en cada job porque lo que importa es el {@code finally}: un
     * hilo del pool del planificador que se quede con el tenant de la orden anterior haría que la
     * siguiente se cobrara contra las credenciales de otra tienda.
     */
    public static void runWithin(Store store, Runnable action) {
        // R1: restaura lo que hubiera en vez de limpiar a secas. Si esto se llama desde un hilo que ya
        // traía tenant —una petición—, limpiar al salir se lo quitaría a lo que venga después dentro
        // de esa misma petición, y ese fallo sería a distancia y muy difícil de ver. Desde un hilo de
        // pool no hay nada que restaurar y el efecto es idéntico al de antes.
        Store previous = CURRENT.get();
        set(store);
        try {
            action.run();
        } finally {
            if (previous == null) {
                clear();
            } else {
                set(previous);
            }
        }
    }
}
