package org.uvo.uvostore.entity.audit.enums;

/**
 * PROD-18. Quién hizo el cambio: un administrador del panel, la API de plataforma (con su operador, si lo manda) o
 * el sistema (nada autenticado: un trabajo programado).
 */
public enum AuditActorType {
    ADMIN,
    PLATFORM,
    SYSTEM
}
