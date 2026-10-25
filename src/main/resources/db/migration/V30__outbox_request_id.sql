-- PROD-15b. El id de la petición que encoló cada fila del outbox (X-Request-Id, ver RequestIdFilter): el retorno de
-- Webpay o el webhook que confirmó el pago, la petición del panel, o el "job-…" de la pasada de conciliación que lo
-- confirmó. Al entregar la fila, OutboxDispatcher lo pone en el MDC y PosClient lo manda al POS en la misma cabecera,
-- así que una venta se sigue entre los dos sistemas con un solo id, y de aquí se llega a los logs de la petición.
--
-- Nullable: las filas anteriores no lo tienen y se entregan igual; la entrega lleva entonces el id de la pasada del
-- despacho. Es una ayuda para correlacionar, no una prueba: el id entrante lo puede elegir quien llama (64
-- caracteres como máximo, el mismo tope que acepta el filtro y que audit_events.request_id).
ALTER TABLE outbox_messages ADD COLUMN request_id VARCHAR(64);

-- REVISIÓN MANUAL:
--
--   Lo que salió (o espera salir) hacia el POS desde una petición concreta:
--     SELECT id, type, status, company_id, created_at, processed_at FROM outbox_messages
--     WHERE request_id = '<id>' ORDER BY id;
--
--   De una venta fallida a la petición que la originó (y de ahí, a los logs):
--     SELECT m.id, o.order_number, m.request_id, m.last_error FROM outbox_messages m JOIN orders o ON o.id = m.order_id
--     WHERE m.status = 'FAILED' AND m.type = 'POS_ORDER' ORDER BY m.id DESC;
