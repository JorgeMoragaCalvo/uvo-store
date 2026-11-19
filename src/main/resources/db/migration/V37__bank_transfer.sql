-- IMP-07. El pago manual pasa de "contra entrega" a transferencia bancaria configurable por tienda.
--
-- Antes el pago manual se ofrecia siempre, sin instrucciones: el comprador no sabia a donde transferir y la
-- orden se cancelaba sola a las 24 horas. Ahora cada tienda lo activa con sus datos bancarios, que el comprador
-- ve en el checkout, en la confirmacion y en el correo, junto con el plazo para pagar. Sin fila, o con
-- enabled = false, la tienda no ofrece el pago manual.
--
-- No guarda secretos: son los datos que la tienda publica a cada comprador. El valor MANUAL de
-- orders.payment_method no cambia.
CREATE TABLE bank_transfer_settings (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT false,
    holder_name VARCHAR(120),
    holder_rut VARCHAR(12),
    bank_name VARCHAR(80),
    account_type VARCHAR(20),
    account_number VARCHAR(30),
    notice_email VARCHAR(255),
    instructions TEXT,
    payment_window_hours INTEGER NOT NULL DEFAULT 48,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT bank_transfer_settings_store_id_key UNIQUE (store_id),
    CONSTRAINT bank_transfer_settings_account_type_valid
        CHECK (account_type IS NULL OR account_type IN ('CORRIENTE', 'VISTA', 'AHORRO', 'RUT')),
    CONSTRAINT bank_transfer_settings_window_valid CHECK (payment_window_hours BETWEEN 1 AND 168),
    -- Activa solo con los datos que el comprador necesita para transferir.
    CONSTRAINT bank_transfer_settings_complete CHECK (NOT enabled OR (
        holder_name IS NOT NULL AND holder_rut IS NOT NULL AND bank_name IS NOT NULL
        AND account_type IS NOT NULL AND account_number IS NOT NULL AND notice_email IS NOT NULL))
);

-- Quien cambio los datos bancarios de una tienda (AuditCatalogConsistencyTest compara esta lista con AuditAction).
ALTER TABLE audit_events DROP CONSTRAINT audit_events_action_valid;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_action_valid CHECK (action IN (
    'REFUND_REQUEST', 'REFUND_ISSUE', 'REFUND_RECORD_EXTERNAL', 'REFUND_RESOLVE',
    'ORDER_CANCEL', 'ORDER_PAYMENT_STATUS_UPDATE', 'ORDER_STOCK_SHORTAGE_RELEASE',
    'OUTBOX_RETRY', 'OUTBOX_RESOLVE',
    'PAYMENT_GATEWAY_UPDATE', 'BANK_TRANSFER_UPDATE', 'SETTINGS_MONEY_UPDATE', 'SETTINGS_STRIPE_UPDATE',
    'POS_CONNECTION_UPDATE',
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

-- REVISION MANUAL:
--
--   Tiendas que ofrecen transferencia y su plazo:
--     SELECT s.slug, b.bank_name, b.account_type, b.payment_window_hours FROM bank_transfer_settings b
--     JOIN stores s ON s.id = b.store_id WHERE b.enabled ORDER BY s.slug;
--
--   Ordenes por transferencia que siguen esperando el pago:
--     SELECT o.order_number, o.total, o.created_at FROM orders o
--     WHERE o.payment_method = 'MANUAL' AND o.payment_status = 'PENDING' AND o.status = 'PENDING'
--     ORDER BY o.created_at;
