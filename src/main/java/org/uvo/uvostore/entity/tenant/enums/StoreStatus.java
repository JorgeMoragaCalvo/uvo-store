package org.uvo.uvostore.entity.tenant.enums;

// G5. Hasta V17 esto era un String libre con valor por defecto "active" en minúscula, y nadie lo
// leía: una tienda suspendida seguía vendiendo igual. Al volverlo enum, el dominio queda definido
// en un solo lugar — el mismo que respalda el CHECK de V18 y el rechazo de TenantResolutionFilter.
public enum StoreStatus {
    ACTIVE,
    SUSPENDED
}
