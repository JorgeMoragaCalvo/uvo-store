-- IMP-06. Los correos de acceso del panel (invitacion de administrador, su reenvio, la invitacion del dueno al
-- crear la tienda, la recuperacion de acceso del dueno y "Olvidaste tu contrasena") salian dentro de la transaccion
-- o justo despues del commit, sin reintento: una caida o un SMTP fuera de servicio dejaba un acceso valido sin su
-- correo, y solo quedaba en el log. Ahora son filas del outbox escritas en la MISMA transaccion que emite el token,
-- en un canal propio (ACCESS), y las entrega OutboxDispatcher como al resto.
--
-- Hasta aqui cada fila colgaba de una orden. Una fila de acceso cuelga de un usuario:
--   store_id    la tienda de la fila, para todas: las consultas por tienda dejan de pasar por la orden.
--   order_id    solo en los tipos de orden.
--   user_id     solo en los tipos de acceso: a quien va el correo.
--   token_hash  solo en los tipos de acceso: SHA-256 (hex) del token emitido. NO el token: la fila no guarda
--               secretos. Al entregar se compara con el token vigente del usuario; si cambio (reenvio, uso,
--               cierre) la fila queda SKIPPED, de modo que un correo viejo nunca lleva un enlace usable.
ALTER TABLE outbox_messages ADD COLUMN store_id BIGINT REFERENCES stores(id) ON DELETE CASCADE;
UPDATE outbox_messages m SET store_id = o.store_id FROM orders o WHERE o.id = m.order_id;
ALTER TABLE outbox_messages ALTER COLUMN store_id SET NOT NULL;

ALTER TABLE outbox_messages ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE outbox_messages ADD COLUMN user_id BIGINT REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE outbox_messages ADD COLUMN token_hash VARCHAR(64);

ALTER TABLE outbox_messages DROP CONSTRAINT outbox_messages_type_valid;
ALTER TABLE outbox_messages ADD CONSTRAINT outbox_messages_type_valid
    CHECK (type IN ('POS_ORDER', 'ORDER_PLACED_EMAIL', 'ORDER_CONFIRMATION_EMAIL', 'CUSTOMER_INVITATION_EMAIL',
                    'ADMIN_INVITATION_EMAIL', 'PASSWORD_RESET_EMAIL'));

-- Una fila es de una orden o de un usuario, nunca de los dos ni de ninguno.
ALTER TABLE outbox_messages ADD CONSTRAINT outbox_messages_subject
    CHECK (CASE WHEN type IN ('ADMIN_INVITATION_EMAIL', 'PASSWORD_RESET_EMAIL')
                THEN order_id IS NULL AND user_id IS NOT NULL AND token_hash IS NOT NULL
                ELSE order_id IS NOT NULL AND user_id IS NULL AND token_hash IS NULL END);

-- La lista de "Envios fallidos" va ahora por tienda y no por orden.
DROP INDEX idx_outbox_messages_failed;
CREATE INDEX idx_outbox_messages_failed ON outbox_messages (store_id) WHERE status = 'FAILED';
CREATE INDEX idx_outbox_messages_user_id ON outbox_messages (user_id) WHERE user_id IS NOT NULL;

-- REVISION MANUAL (lo normal es el panel o la API de plataforma, no esto):
--
--   Correos de acceso pendientes o fallidos, sin el token (no esta en la fila):
--     SELECT m.id, m.store_id, m.type, u.email, m.status, m.attempts, m.last_error, m.created_at
--     FROM outbox_messages m JOIN users u ON u.id = m.user_id
--     WHERE m.type IN ('ADMIN_INVITATION_EMAIL', 'PASSWORD_RESET_EMAIL') AND m.status <> 'SENT'
--     ORDER BY m.id DESC;
--
--   Reintentar uno fallido desde el panel ("Envios fallidos") o desde la plataforma:
--     POST /api/platform/access-emails/{id}/retry   {"note": "..."}
--   Reintentar solo sirve si el token sigue vigente y es el actual; si no, la fila queda SKIPPED y la salida es
--   reenviar la invitacion (panel) o la recuperacion de acceso del dueno (plataforma), que emiten uno nuevo.
