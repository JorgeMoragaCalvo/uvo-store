-- IMP-08. La tienda adopta el contrato UvoPOS <-> uvo-store v1 (docs/contrato-pos-ecommerce.md).
--
-- 1) Llaves por direccion. Cada conexion tiene una llave para firmar lo que la tienda manda al POS
--    (OUTBOUND) y otra con la que el POS firma lo que manda a la tienda (INBOUND), distintas para que la fuga
--    de un lado no permita hacerse pasar por el otro. Una rotacion deja la llave anterior viva 7 dias
--    (retires_at); una revocacion la mata en el acto (revoked_at). El secreto va cifrado con AES-GCM
--    (EncryptedStringConverter) y es base64 de al menos 32 bytes.
CREATE TABLE pos_connection_keys (
    id BIGSERIAL PRIMARY KEY,
    connection_id BIGINT NOT NULL REFERENCES pos_connections(id) ON DELETE CASCADE,
    direction VARCHAR(10) NOT NULL,
    key_id VARCHAR(100) NOT NULL,
    secret VARCHAR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    retires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT pos_connection_keys_direction_valid CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    CONSTRAINT pos_connection_keys_key_unique UNIQUE (direction, key_id)
);

CREATE INDEX idx_pos_connection_keys_connection ON pos_connection_keys (connection_id, direction);

-- 2) El esquema anterior (token Bearer uvp_ y X-Signature) desaparece. Ningun POS real lo uso, y un token
--    viejo no acredita una conexion v1: las conexiones existentes quedan inactivas hasta que se carguen sus
--    llaves nuevas.
ALTER TABLE pos_connections DROP COLUMN api_key;
ALTER TABLE pos_connections DROP COLUMN webhook_secret;
UPDATE pos_connections SET is_active = false;

-- 3) El antirreplay pasa de la firma al nonce (contrato 3.3): un nonce ya visto con ese key_id dentro de la
--    ventana se rechaza. Lo de antes no sirve con el esquema nuevo, asi que la tabla se rehace vacia.
DROP TABLE pos_webhook_receipts;
CREATE TABLE pos_webhook_receipts (
    id BIGSERIAL PRIMARY KEY,
    key_id VARCHAR(100) NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pos_webhook_receipts_unique UNIQUE (key_id, nonce)
);
CREATE INDEX idx_pos_webhook_receipts_received_at ON pos_webhook_receipts (received_at);

-- 4) Cada variante es un Producto del POS (contrato 4). external_variant_id pasa a ser el id de ese
--    Producto, unico por empresa, y cada variante lleva su propia version (catalogo y stock, contrato 5).
--    company_id se copia del mapeo padre.
ALTER TABLE product_variation_sync_mapping ADD COLUMN company_id BIGINT;
UPDATE product_variation_sync_mapping vm SET company_id = m.company_id
    FROM product_sync_mapping m WHERE m.id = vm.mapping_id;
ALTER TABLE product_variation_sync_mapping ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE product_variation_sync_mapping ADD COLUMN pos_version BIGINT;
ALTER TABLE product_variation_sync_mapping
    ADD CONSTRAINT pvsm_company_external_unique UNIQUE (company_id, external_variant_id);

-- 5) El padre de un producto con variantes no es un Producto del POS: su mapeo guarda la empresa y los flags,
--    sin external_id. catalog_version pasa a ser la version unica del producto (catalogo y stock).
ALTER TABLE product_sync_mapping ALTER COLUMN external_id DROP NOT NULL;
ALTER TABLE product_sync_mapping ALTER COLUMN external_sku DROP NOT NULL;
UPDATE product_sync_mapping m SET external_id = NULL, external_sku = NULL
    FROM products p WHERE p.id = m.product_id AND p.product_type = 'VARIABLE';

-- 6) Exento de IVA: lo fija el POS (contrato 4) y el IVA de la linea es 0. La linea de la orden ya guarda su
--    tax_amount (V33), asi que no necesita el flag.
ALTER TABLE products ADD COLUMN tax_exempt BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE product_variations ADD COLUMN tax_exempt BOOLEAN NOT NULL DEFAULT false;

-- 7) El cuerpo de una venta al POS se fija la primera vez que se prepara y los reintentos mandan esos mismos
--    bytes (contrato 8: misma clave con otro hash = IDEMPOTENCY_CONFLICT). pos_sale_id es lo que devolvio el POS.
ALTER TABLE outbox_messages ADD COLUMN payload TEXT;
ALTER TABLE outbox_messages ADD COLUMN pos_sale_id BIGINT;

-- 8) Quien revoco una llave del POS (AuditCatalogConsistencyTest compara esta lista con AuditAction).
ALTER TABLE audit_events DROP CONSTRAINT audit_events_action_valid;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_action_valid CHECK (action IN (
    'REFUND_REQUEST', 'REFUND_ISSUE', 'REFUND_RECORD_EXTERNAL', 'REFUND_RESOLVE',
    'ORDER_CANCEL', 'ORDER_PAYMENT_STATUS_UPDATE', 'ORDER_STOCK_SHORTAGE_RELEASE',
    'OUTBOX_RETRY', 'OUTBOX_RESOLVE',
    'PAYMENT_GATEWAY_UPDATE', 'BANK_TRANSFER_UPDATE', 'SETTINGS_MONEY_UPDATE', 'SETTINGS_STRIPE_UPDATE',
    'POS_CONNECTION_UPDATE', 'POS_KEY_REVOKE',
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
--   Conexiones y sus llaves vigentes (sin secretos):
--     SELECT c.company_id, c.is_active, k.direction, k.key_id, k.created_at, k.retires_at, k.revoked_at
--     FROM pos_connections c LEFT JOIN pos_connection_keys k ON k.connection_id = c.id
--     ORDER BY c.company_id, k.direction, k.created_at DESC;
--
--   Variantes vinculadas a un Producto del POS:
--     SELECT vm.company_id, vm.external_variant_id, vm.product_variation_id, vm.pos_version
--     FROM product_variation_sync_mapping vm ORDER BY vm.company_id, vm.external_variant_id;
