-- POS-3. Las entradas del POS, ordenadas e idempotentes.
--
-- 1) La versión de catálogo aplicada a cada mapeo. product.updated y la sincronización la traen
--    obligatoria (un número que crece con cada cambio del producto en el POS), y una entrada con una versión
--    igual o menor que esta se descarta en vez de pisar algo más nuevo. NULL = todavía no se aplicó ninguna:
--    los mapeos anteriores a esta migración aceptan el primer evento que llegue. El stock no usa esta columna:
--    lo ordena el old_stock de stock.updated (F13).
ALTER TABLE product_sync_mapping ADD COLUMN catalog_version BIGINT;

-- 2) Un evento descartado no es un fallo: es lo esperable cuando el POS entrega fuera de orden o repite.
ALTER TABLE sync_webhook_logs DROP CONSTRAINT sync_webhook_logs_status_valid;
ALTER TABLE sync_webhook_logs ADD CONSTRAINT sync_webhook_logs_status_valid
    CHECK (status IN ('RECEIVED', 'PROCESSING', 'SUCCESS', 'FAILED', 'DISCARDED'));

-- 3) El antirreplay de los webhooks (F21), que vivía en una caché en memoria y se perdía con cada reinicio.
--    La firma ya verificada es el id del evento: HMAC(cuerpo + timestamp, secreto del comercio), así que dos
--    eventos distintos no pueden compartirla. Sin store_id: la empresa la identifica, como en
--    product_sync_mapping.
CREATE TABLE pos_webhook_receipts (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    signature VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pos_webhook_receipts_unique UNIQUE (company_id, signature)
);

-- Lo único que busca la purga (PosWebhookReplayGuard): lo que ya no puede volver a ser fresco.
CREATE INDEX idx_pos_webhook_receipts_received_at ON pos_webhook_receipts (received_at);

-- REVISIÓN MANUAL:
--
--   Lo que se descartó y por qué:
--     SELECT id, company_id, event_type, error_message, created_at
--     FROM sync_webhook_logs WHERE status = 'DISCARDED' ORDER BY id DESC LIMIT 50;
--
--   La versión aplicada de un producto del POS:
--     SELECT product_id, external_id, company_id, catalog_version, last_synced_at
--     FROM product_sync_mapping WHERE external_id = <id del POS> AND company_id = <empresa>;
