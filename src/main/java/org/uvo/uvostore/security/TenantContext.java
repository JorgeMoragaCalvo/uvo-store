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

    public static void clear() {
        CURRENT.remove();
    }
}
