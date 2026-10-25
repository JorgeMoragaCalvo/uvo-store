-- PROD-18. Registro de auditoría: quién cambió qué, en qué tienda, cuándo, con qué resultado y en qué petición.
-- Hasta aquí no existía (lo reconocía V19 en order_refunds.user_id, G6): solo los reembolsos guardaban quién los
-- hizo, y order_status_history tiene un user_id que nadie llena.
--
-- Se escribe en la MISMA transacción que el cambio (AuditTrail.record es MANDATORY): si el cambio se deshace, la
-- fila también, y si la fila no se puede escribir, el cambio no se hace. Las dos excepciones, deliberadas: el login
-- fallido confirma su transacción (noRollbackFor) y el reembolso registra la intención y el fallo dentro de las
-- transacciones propias que RefundIntentStore ya confirmaba.
--
-- Sin claves foráneas, a propósito: la historia tiene que sobrevivir a la tienda, al usuario y al objeto que
-- nombra. store_id, actor_id y target_id son números, no referencias; actor_label guarda el correo del admin tal
-- como era en ese momento, o el operador de plataforma (X-Platform-Operator).
--
-- details es un resumen que nunca lleva secretos ni cuerpos completos: valores antes/después de campos no secretos,
-- y para los secretos solo el nombre de la clave y si se puso, se reemplazó o se borró (AuditDiff).
CREATE TABLE audit_events (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    store_id BIGINT,
    actor_type VARCHAR(20) NOT NULL,
    actor_id BIGINT,
    actor_label VARCHAR(255),
    action VARCHAR(50) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT,
    result VARCHAR(10) NOT NULL,
    request_id VARCHAR(64),
    details JSONB,

    -- Columnas enum (B6): cada valor nuevo de AuditAction, AuditTargetType, AuditActorType o AuditResult necesita
    -- su migración. AuditCatalogConsistencyTest compara estas listas con los enums.
    CONSTRAINT audit_events_actor_type_valid CHECK (actor_type IN ('ADMIN', 'PLATFORM', 'SYSTEM')),
    CONSTRAINT audit_events_result_valid CHECK (result IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT audit_events_target_type_valid CHECK (target_type IN (
        'STORE', 'USER', 'ROLE', 'ORDER', 'PAYMENT_GATEWAY_CONFIG', 'POS_CONNECTION', 'SHIPPING_METHOD',
        'SHIPPING_RATE', 'COUPON', 'PRODUCT', 'PRODUCT_VARIATION')),
    CONSTRAINT audit_events_action_valid CHECK (action IN (
        'REFUND_REQUEST', 'REFUND_ISSUE', 'REFUND_RECORD_EXTERNAL',
        'ORDER_CANCEL', 'ORDER_PAYMENT_STATUS_UPDATE',
        'PAYMENT_GATEWAY_UPDATE', 'SETTINGS_MONEY_UPDATE', 'SETTINGS_STRIPE_UPDATE', 'POS_CONNECTION_UPDATE',
        'SHIPPING_METHOD_CREDENTIALS_UPDATE',
        'USER_CREATE', 'USER_UPDATE', 'USER_ACTIVATE', 'USER_DEACTIVATE', 'USER_DELETE', 'USER_INVITATION_RESEND',
        'USER_INVITATION_ACCEPT', 'USER_PASSWORD_RESET', 'AUTH_LOGIN',
        'ROLE_CREATE', 'ROLE_UPDATE', 'ROLE_DELETE',
        'STORE_CREATE', 'STORE_DOMAIN_UPDATE', 'STORE_DOMAIN_VERIFY', 'STORE_STATUS_UPDATE',
        'COUPON_CREATE', 'COUPON_UPDATE', 'COUPON_DELETE', 'COUPON_STATUS_TOGGLE',
        'SHIPPING_RATE_CREATE', 'SHIPPING_RATE_UPDATE', 'SHIPPING_RATE_DELETE', 'SHIPPING_RATE_STATUS_TOGGLE',
        'SHIPPING_METHOD_CREATE', 'SHIPPING_METHOD_UPDATE', 'SHIPPING_METHOD_DELETE', 'SHIPPING_METHOD_STATUS_TOGGLE',
        'PRODUCT_PRICE_UPDATE', 'VARIATION_PRICE_UPDATE')),
    -- Un admin sin id no se puede atribuir a nadie.
    CONSTRAINT audit_events_admin_has_id CHECK (actor_type <> 'ADMIN' OR actor_id IS NOT NULL)
);

-- Lo que se va a preguntar: qué pasó en esta tienda, y qué le pasó a este objeto.
CREATE INDEX idx_audit_events_store_occurred ON audit_events (store_id, occurred_at);
CREATE INDEX idx_audit_events_target ON audit_events (target_type, target_id);

-- Una fila de auditoría no se corrige: se agrega otra. El disparador frena cualquier UPDATE, venga de la aplicación
-- o de una consola. DELETE sigue permitido para la retención (por decidir), el cierre de una tienda (PROD-23) y la
-- limpieza de los tests que confirman.
CREATE FUNCTION audit_events_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events es de solo inserción (PROD-18)';
END;
$$;

CREATE TRIGGER audit_events_no_update BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();

-- REVISIÓN MANUAL:
--
--   Lo último que pasó en una tienda:
--     SELECT occurred_at, actor_type, actor_label, action, target_type, target_id, result, details
--     FROM audit_events WHERE store_id = <tienda> ORDER BY occurred_at DESC LIMIT 100;
--
--   La historia de un objeto (una orden, un usuario, un rol...):
--     SELECT occurred_at, actor_label, action, result, details
--     FROM audit_events WHERE target_type = 'ORDER' AND target_id = <id> ORDER BY occurred_at;
--
--   Lo que hizo un admin (por id: el correo puede haber cambiado después):
--     SELECT occurred_at, store_id, action, target_type, target_id, result
--     FROM audit_events WHERE actor_type = 'ADMIN' AND actor_id = <users.id> ORDER BY occurred_at DESC;
--
--   Lo que hizo un operador de plataforma (o la plataforma sin operador, actor_label IS NULL):
--     SELECT occurred_at, store_id, action, target_id, details
--     FROM audit_events WHERE actor_type = 'PLATFORM' AND actor_label = '<operador>' ORDER BY occurred_at DESC;
--
--   Logins fallidos de las últimas 24 horas, por cuenta y motivo:
--     SELECT store_id, actor_id, actor_label, details->>'reason' AS motivo, count(*)
--     FROM audit_events WHERE action = 'AUTH_LOGIN' AND result = 'FAILURE' AND occurred_at > now() - interval '24 hours'
--     GROUP BY 1, 2, 3, 4 ORDER BY 5 DESC;
--
--   Cambios de dinero y de credenciales:
--     SELECT occurred_at, store_id, actor_label, action, details FROM audit_events
--     WHERE action IN ('SETTINGS_MONEY_UPDATE', 'SETTINGS_STRIPE_UPDATE', 'PAYMENT_GATEWAY_UPDATE',
--                      'POS_CONNECTION_UPDATE', 'SHIPPING_METHOD_CREDENTIALS_UPDATE', 'ORDER_PAYMENT_STATUS_UPDATE')
--     ORDER BY occurred_at DESC;
--
--   Desactivaciones de admins, que llegan por dos caminos (el interruptor y la edición):
--     SELECT occurred_at, store_id, actor_label, target_id, action FROM audit_events
--     WHERE action = 'USER_DEACTIVATE'
--        OR (action = 'USER_UPDATE' AND details->'changes'->'active'->>'to' = 'false');
--
--   Seguir una petición: el request_id es la cabecera X-Request-Id de la respuesta, y aparece entre corchetes en
--   cada línea de log de esa petición.
--     SELECT * FROM audit_events WHERE request_id = '<X-Request-Id>';
--
--   Retención, cuando se decida (el disparador no frena DELETE):
--     DELETE FROM audit_events WHERE occurred_at < now() - interval '<plazo>';
