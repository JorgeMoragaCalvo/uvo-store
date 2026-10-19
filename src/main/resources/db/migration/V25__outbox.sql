-- PROD-13b. Los efectos que una orden produce después del commit —registrar la venta en el POS y los
-- correos al comprador— corrían en ejecutores en memoria. Una caída o un redespliegue con la cola llena
-- los perdía, y nada registraba qué había salido. El hueco más grave era el del POS: el reintento solo
-- miraba órdenes con sync_attempts >= 1, así que una venta cuyo proceso murió antes del primer intento no
-- llegaba nunca al POS, que es el maestro del stock.
--
-- Ahora cada efecto es una fila escrita en la MISMA transacción que lo origina (el checkout o el pago
-- confirmado), y un despachador programado la entrega (OutboxDispatcher). Si la transacción se deshace,
-- la fila también; si se confirma, el efecto sobrevive a cualquier caída posterior.
--
-- Sin store_id: es hija de orders y hereda la tienda por order_id, la convención de V8.
CREATE TABLE outbox_messages (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    type VARCHAR NOT NULL,
    -- Solo POS_ORDER: la empresa del POS a la que va esta venta. Una orden con productos de dos empresas
    -- genera dos filas, para que el fallo de una no obligue a reenviar a la otra.
    company_id BIGINT,
    -- La red de seguridad contra el doble encolado: dos confirmaciones del mismo pago chocan aquí y la
    -- segunda transacción se deshace entera. Por eso el encolado es un INSERT simple, sin ON CONFLICT.
    dedup_key VARCHAR NOT NULL,
    status VARCHAR NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    CONSTRAINT outbox_messages_dedup_key_unique UNIQUE (dedup_key),
    CONSTRAINT outbox_messages_type_valid
        CHECK (type IN ('POS_ORDER', 'ORDER_PLACED_EMAIL', 'ORDER_CONFIRMATION_EMAIL', 'CUSTOMER_INVITATION_EMAIL')),
    CONSTRAINT outbox_messages_status_valid
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT', 'SKIPPED', 'FAILED')),
    -- Una venta al POS sin empresa no se puede entregar. La única excepción son los fallos heredados de
    -- abajo, que ya llegan como FAILED.
    CONSTRAINT outbox_messages_pos_company
        CHECK (type <> 'POS_ORDER' OR company_id IS NOT NULL OR status = 'FAILED')
);

-- Lo único que el despachador busca: lo pendiente o en vuelo de su canal. Parcial porque las filas
-- entregadas, que serán casi todas, no le interesan.
CREATE INDEX idx_outbox_messages_due ON outbox_messages (type, next_attempt_at)
    WHERE status IN ('PENDING', 'IN_FLIGHT');
CREATE INDEX idx_outbox_messages_order_id ON outbox_messages (order_id);

-- Las tres columnas que llevaba el reintento anterior dejan de tener lector. Antes de borrarlas se guarda
-- como FAILED lo único que registraban de valor: las ventas que intentaron llegar al POS y no lo
-- consiguieron. Sin esto, el DROP borraría el único rastro de esos fallos. (En ningún entorno se ha
-- encendido el POS todavía, así que lo esperable es que no inserte nada.)
INSERT INTO outbox_messages (order_id, type, dedup_key, status, attempts, last_error, updated_at)
SELECT id, 'POS_ORDER', 'pos-legacy:' || id, 'FAILED', sync_attempts,
       COALESCE(last_sync_error, 'Fallo de notificación al POS anterior al outbox'), now()
FROM orders
WHERE pos_synced = false AND sync_attempts >= 1;

ALTER TABLE orders DROP COLUMN pos_synced;
ALTER TABLE orders DROP COLUMN sync_attempts;
ALTER TABLE orders DROP COLUMN last_sync_error;

-- DESPLIEGUE: sin solapar instancias. Una instancia anterior que siga viva escribe pos_synced y
-- sync_attempts al crear cada orden, y tras esta migración fallaría todo checkout.
--
-- REVISIÓN MANUAL de un fallo definitivo (status = 'FAILED'; cada uno avisa a Sentry al ocurrir):
--
--   Ver qué hay:
--     SELECT id, order_id, type, company_id, attempts, last_error, updated_at
--     FROM outbox_messages WHERE status = 'FAILED' ORDER BY id;
--
--   Volver a intentarlo:
--     UPDATE outbox_messages SET status = 'PENDING', next_attempt_at = now(), updated_at = now()
--     WHERE id = <id>;
--
--   Descartarlo (ya se resolvió por otra vía):
--     UPDATE outbox_messages SET status = 'SKIPPED', processed_at = now(), updated_at = now()
--     WHERE id = <id>;
--
-- CUIDADO con POS_ORDER: un fallo "incierto" o "huérfano en vuelo" significa que la venta PUDO haber
-- llegado al POS. Mientras el POS no confirme que respeta la clave de idempotencia (POS-1), reencolarla
-- sin comprobarlo antes en el POS puede emitir un segundo documento tributario.
--
-- Los correos omitidos por falta de SMTP quedan como SKIPPED con last_error = 'smtp-not-configured', y
-- se pueden reencolar igual una vez configurado.
