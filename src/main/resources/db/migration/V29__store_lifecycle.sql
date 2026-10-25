-- PROD-23. El ciclo de vida de una tienda desde la plataforma: suspender, reactivar, recuperar el acceso del dueño y
-- cerrar. Hasta aquí SUSPENDED existía (V18) pero nada lo escribía; solo se ponía con SQL.
--
-- CLOSED es terminal: la tienda ya no tiene accesos, credenciales, conexión con el POS ni dominio, y sus datos se
-- conservan hasta que se purguen con el procedimiento documentado en CLAUDE.md (no con la API). El nick sigue
-- ocupado: stores.slug es UNIQUE y la fila no se borra hasta la purga.
ALTER TABLE stores DROP CONSTRAINT stores_status_valid;
ALTER TABLE stores ADD CONSTRAINT stores_status_valid
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'));

-- Cuándo cambió el estado por última vez. NULL = nunca cambió desde el alta. Lo muestra la lista de la plataforma
-- ("suspendida desde") y es de donde se cuenta la retención de una tienda cerrada antes de purgarla.
ALTER TABLE stores ADD COLUMN status_changed_at TIMESTAMPTZ;

-- Las dos acciones nuevas de la auditoría (B6: AuditCatalogConsistencyTest compara esta lista con AuditAction).
-- STORE_STATUS_UPDATE ya estaba, reservada desde V28 para esta tarea.
ALTER TABLE audit_events DROP CONSTRAINT audit_events_action_valid;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_action_valid CHECK (action IN (
    'REFUND_REQUEST', 'REFUND_ISSUE', 'REFUND_RECORD_EXTERNAL',
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
--   Tiendas por estado:
--     SELECT status, count(*) FROM stores GROUP BY status;
--
--   Suspendidas y cerradas, con desde cuándo:
--     SELECT id, slug, status, status_changed_at FROM stores WHERE status <> 'ACTIVE' ORDER BY status_changed_at;
--
--   Qué se hizo con el estado de una tienda, y quién:
--     SELECT occurred_at, actor_label, action, details FROM audit_events
--     WHERE store_id = <tienda> AND action IN ('STORE_STATUS_UPDATE', 'STORE_CLOSE', 'STORE_OWNER_ACCESS_RECOVERY')
--     ORDER BY occurred_at;
--
--   Cerradas que ya cumplieron la retención (el procedimiento de purga está en CLAUDE.md):
--     SELECT id, slug, status_changed_at FROM stores
--     WHERE status = 'CLOSED' AND status_changed_at < now() - interval '<plazo>';
