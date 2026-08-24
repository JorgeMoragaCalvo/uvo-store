package org.uvo.uvostore.security;

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
            throw new IllegalStateException("No se pudo determinar la tienda para esta solicitud");
        }
        return store;
    }

    public static Long requireStoreId() {
        return requireCurrent().getId();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
