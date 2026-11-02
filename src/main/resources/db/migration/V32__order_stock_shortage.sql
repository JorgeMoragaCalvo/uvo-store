-- GAP-03. Una orden cobrada a la que no se le pudo descontar alguna línea (dos pagos por la última unidad, o venta
-- simultánea en mostrador y web) queda marcada y no se puede despachar hasta resolverla desde el panel:
--   POST /api/admin/orders/{id}/stock-shortage/retry    vuelve a descontar las líneas que faltan (si llegó stock);
--                                                       la marca se quita sola cuando ya no falta ninguna.
--   POST /api/admin/orders/{id}/stock-shortage/release  quita la marca con una nota (se consiguió por fuera, o se
--                                                       reembolsó la parte que faltaba). Queda auditado.
-- El reembolso total y la cancelación siguen como antes. La reserva de stock coordinada con el POS es otro alcance.
ALTER TABLE orders ADD COLUMN stock_shortage boolean NOT NULL DEFAULT false;

-- Las órdenes que ya quedaron así antes de esta migración. Se reconocen por la nota de reportStockFailure y no por
-- order_items.stock_applied, porque una línea con manage_stock=false también queda en false sin que le falte nada.
-- Solo las que siguen sin despachar: una que el comerciante ya resolvió por fuera y sigue en proceso queda bloqueada
-- igual, y se libera con una nota.
UPDATE orders o
   SET stock_shortage = true
 WHERE o.payment_status = 'PAID'
   AND o.status IN ('PENDING', 'PAID', 'PROCESSING')
   AND EXISTS (SELECT 1
                 FROM order_status_history h
                WHERE h.order_id = o.id
                  AND h.notes LIKE 'Stock insuficiente al confirmar el pago%');

-- La acción nueva de la auditoría (B6: AuditCatalogConsistencyTest compara esta lista con AuditAction).
ALTER TABLE audit_events DROP CONSTRAINT audit_events_action_valid;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_action_valid CHECK (action IN (
    'REFUND_REQUEST', 'REFUND_ISSUE', 'REFUND_RECORD_EXTERNAL', 'REFUND_RESOLVE',
    'ORDER_CANCEL', 'ORDER_PAYMENT_STATUS_UPDATE', 'ORDER_STOCK_SHORTAGE_RELEASE',
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
--   Órdenes cobradas sin stock y sin resolver, de todas las tiendas:
--     SELECT id, store_id, order_number, status, paid_at FROM orders
--     WHERE stock_shortage AND payment_status = 'PAID' AND status <> 'CANCELLED' ORDER BY paid_at;
--
--   Quién liberó qué:
--     SELECT occurred_at, actor_label, details FROM audit_events
--     WHERE store_id = <tienda> AND action = 'ORDER_STOCK_SHORTAGE_RELEASE' ORDER BY occurred_at;
