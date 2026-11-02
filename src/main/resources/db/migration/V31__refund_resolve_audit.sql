-- GAP-02. Una intención de reembolso PENDING (F12, V21) ya se puede resolver desde el panel:
-- POST /api/admin/orders/{id}/refunds/{refundId}/resolve, con el permiso orders.refund. Reemplaza el UPDATE a mano
-- que V21 dejaba anotado como pendiente. No llama a la pasarela: el operador dice si el dinero salió (COMPLETED, con
-- la referencia) o no (FAILED, y el importe vuelve a quedar disponible), y eso queda auditado.

-- La acción nueva de la auditoría (B6: AuditCatalogConsistencyTest compara esta lista con AuditAction).
ALTER TABLE audit_events DROP CONSTRAINT audit_events_action_valid;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_action_valid CHECK (action IN (
    'REFUND_REQUEST', 'REFUND_ISSUE', 'REFUND_RECORD_EXTERNAL', 'REFUND_RESOLVE',
    'ORDER_CANCEL', 'ORDER_PAYMENT_STATUS_UPDATE',
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
--   Intenciones sin resolver, de todas las tiendas (lo mismo que la métrica uvostore_refunds_pending):
--     SELECT r.id, o.store_id, o.order_number, r.amount, r.created_at FROM order_refunds r
--     JOIN orders o ON o.id = r.order_id WHERE r.status = 'PENDING' ORDER BY r.created_at;
--
--   Quién resolvió qué:
--     SELECT occurred_at, actor_label, details FROM audit_events
--     WHERE store_id = <tienda> AND action = 'REFUND_RESOLVE' ORDER BY occurred_at;
