-- PROD-13a: el stock de una orden se descuenta en StockDecrementListener, DESPUÉS del commit del pago.
-- Si el proceso muere en medio, la orden queda PAID con stock_applied = false y nadie lo detecta. El
-- barrido que lo repara (StockRecoveryJob) tiene que dejar pasar un margen antes de tocar una orden,
-- o competiría con el listener de un pago que acaba de confirmarse, y para eso hace falta saber
-- CUÁNDO se pagó. updated_at no sirve: lo mueve cualquier cosa (el aviso al POS, el seguimiento, el
-- panel), y created_at es de cuando se abrió el checkout, no de cuando se cobró.
--
-- Nullable a propósito: solo las órdenes que llegaron a pagarse tienen fecha de pago.
ALTER TABLE orders ADD COLUMN paid_at TIMESTAMPTZ;

-- Backfill de las órdenes ya pagadas. La mejor lectura disponible es la entrada 'Pago confirmado' que
-- markPaid deja en el historial desde siempre; si falta (órdenes muy antiguas o tocadas a mano), se cae
-- a updated_at y por último a created_at, que siempre es anterior o igual al pago real. Errar hacia
-- atrás solo adelanta el barrido de una orden, nunca lo retrasa.
--
-- Consecuencia asumida: una orden PAID que al migrar siga con stock_applied = false —el caso exacto que
-- este cambio viene a recoger— la recogerá el primer barrido, con su nota en el historial y su aviso.
UPDATE orders o SET paid_at = COALESCE(
        (SELECT MIN(h.created_at)
           FROM order_status_history h
          WHERE h.order_id = o.id
            AND h.notes = 'Pago confirmado'),
        o.updated_at,
        o.created_at)
WHERE o.payment_status IN ('PAID', 'REFUNDED');
