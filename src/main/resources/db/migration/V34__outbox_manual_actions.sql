-- GAP-04. Las filas FAILED del outbox se ven y se resuelven desde el panel («Envíos fallidos»), en vez de con el SQL
-- a mano de V25:
--   POST /api/admin/outbox/{id}/retry    la devuelve a la cola (PENDING) con una nota. Una venta al POS de resultado
--                                        incierto exige además confirmar que se revisó el POS (POS-1).
--   POST /api/admin/outbox/{id}/resolve  la cierra como SKIPPED sin reenviar, con una nota (ya se resolvió por otra
--                                        vía). Es la única salida de las filas heredadas de V25 (company_id NULL),
--                                        que su CHECK no deja volver a PENDING.
-- Las dos quedan en el historial de la orden y en la auditoría.

-- El plazo de abandono del despachador (app.outbox.give-up-after-hours) se medía desde created_at, así que una fila
-- vieja reencolada volvía a FAILED en su primer fallo reintentable. Ahora se mide desde el último reencolado manual.
ALTER TABLE outbox_messages ADD COLUMN requeued_at timestamptz;

-- Una venta heredada (sin empresa) solo podía estar FAILED, así que tampoco se podía cerrar: el "Descartarlo" de V25
-- violaba esta misma regla. SKIPPED es terminal y el despachador nunca lo reclama, así que no hay riesgo de enviarla
-- sin saber a qué empresa. PENDING e IN_FLIGHT siguen prohibidos.
ALTER TABLE outbox_messages DROP CONSTRAINT outbox_messages_pos_company;
ALTER TABLE outbox_messages ADD CONSTRAINT outbox_messages_pos_company
    CHECK (type <> 'POS_ORDER' OR company_id IS NOT NULL OR status IN ('FAILED', 'SKIPPED'));

-- La lista del panel. El índice de V25 solo cubre PENDING e IN_FLIGHT.
CREATE INDEX idx_outbox_messages_failed ON outbox_messages (order_id) WHERE status = 'FAILED';

-- Las acciones nuevas de la auditoría (B6: AuditCatalogConsistencyTest compara esta lista con AuditAction).
ALTER TABLE audit_events DROP CONSTRAINT audit_events_action_valid;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_action_valid CHECK (action IN (
    'REFUND_REQUEST', 'REFUND_ISSUE', 'REFUND_RECORD_EXTERNAL', 'REFUND_RESOLVE',
    'ORDER_CANCEL', 'ORDER_PAYMENT_STATUS_UPDATE', 'ORDER_STOCK_SHORTAGE_RELEASE',
    'OUTBOX_RETRY', 'OUTBOX_RESOLVE',
    'PAYMENT_GATEWAY_UPDATE', 'SETTINGS_MONEY_UPDATE', 'SETTINGS_STRIPE_UPDATE', 'POS_CONNECTION_UPDATE',
    'SHIPPING_METHOD_CREDENTIALS_UPDATE',
    'USER_CREATE', 'USER_UPDATE', 'USER_ACTIVATE', 'USER_DEACTIVATE', 'USER_DELETE', 'USER_INVITATION_RESEND',
    'USER_INVITATION_ACCEPT', 'USER_PASSWORD_RESET', 'AUTH_LOGIN',
    'ROLE_CREATE', 'ROLE_UPDATE', 'ROLE_DELETE',
    'STORE_CREATE', 'STORE_DOMAIN_UPDATE', 'STORE_DOMAIN_VERIFY', 'STORE_STATUS_UPDATE', 'STORE_CLOSE',
    'STORE_OWNER_ACCESS_RECOVERY',
    'COUPON_CREATE', 'COUPON_UPDATE', 'COUPON_DELETE', 'COUPON_STATUS_TOGGLE',
    'SHIPPING_RATE_CREATE', 'SHIPPING_RATE_UPDATE', 'SHIPPING_RATE_DELETE', 'SHIPPING_RATE_STATUS_TOGGLE',
    'SHIPPING_METHOD_CREATE', 'SHIPPING_METHOD_UPDATE', 'SHIPPING_METHOD_DELETE', 'SHIPPING_METHOD_STATUS_TOGGLE',
    'PRODUCT_PRICE_UPDATE', 'VARIATION_PRICE_UPDATE'));

-- REVISIÓN MANUAL:
--
--   Quién reintentó o resolvió qué:
--     SELECT occurred_at, actor_label, action, details FROM audit_events
--     WHERE store_id = <tienda> AND action IN ('OUTBOX_RETRY', 'OUTBOX_RESOLVE') ORDER BY occurred_at;
--
--   Las filas resueltas a mano llevan last_error = 'Resuelta a mano: <nota>'.
