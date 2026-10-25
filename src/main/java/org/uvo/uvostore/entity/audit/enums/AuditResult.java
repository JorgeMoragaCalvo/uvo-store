package org.uvo.uvostore.entity.audit.enums;

/**
 * PROD-18. Casi todo es SUCCESS: lo rechazado se deshace junto con su fila. Los dos FAILURE que sí quedan son el
 * login fallido de una cuenta existente y el reembolso que la pasarela rechazó.
 */
public enum AuditResult {
    SUCCESS,
    FAILURE
}
