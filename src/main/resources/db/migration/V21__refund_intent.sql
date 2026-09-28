-- F12. El reembolso llamaba a la pasarela DENTRO de la transacción y guardaba la fila después. El
-- caso que eso no cubre no es "la pasarela falla" —ahí se lanza, se deshace y no queda rastro de un
-- reembolso que no ocurrió, que es correcto— sino el contrario: la pasarela **devuelve el dinero** y
-- luego se cae lo local (la base, la red, el proceso). Entonces el dinero salió y no queda ni una fila,
-- así que `totalRefunded` sigue diciendo que no se ha devuelto nada: el operador reintenta y se
-- devuelve dos veces.
--
-- La intención se escribe ahora ANTES de llamar, y en su propia transacción, para que ese commit
-- sobreviva a que todo lo demás se caiga.

-- COMPLETED por defecto, y eso es lo que hace segura la migración: todas las filas que ya existen son
-- reembolsos consumados, no intenciones a medias.
ALTER TABLE order_refunds ADD COLUMN status VARCHAR NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE order_refunds ADD CONSTRAINT order_refunds_status_valid
    CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'));

-- La red de seguridad de verdad no es la lógica de la aplicación, es esta restricción: hace imposible
-- guardar dos veces la misma intención, aunque el código se equivoque. La clave se deriva de datos
-- estables (orden + ya devuelto + importe), así que el reintento de la MISMA devolución la repite y un
-- segundo parcial legítimo genera otra distinta.
ALTER TABLE order_refunds ADD COLUMN idempotency_key VARCHAR;
ALTER TABLE order_refunds ADD CONSTRAINT order_refunds_idempotency_key_unique UNIQUE (idempotency_key);

-- El saldo por devolver suma PENDING y COMPLETED, nunca FAILED: una intención pendiente puede haberse
-- cobrado en la pasarela, así que ese dinero deja de estar disponible hasta que alguien lo aclare.
CREATE INDEX idx_order_refunds_order_id_status ON order_refunds(order_id, status);

-- PENDIENTE (no hay endpoint todavía): resolver a mano una intención incierta. Si al comprobarlo en la
-- pasarela el dinero SÍ salió, la fila se deja como está — el saldo ya es correcto — o se pasa a
-- COMPLETED con su referencia. Si NO salió:
--     UPDATE order_refunds SET status = 'FAILED' WHERE id = <id>;
-- y el saldo vuelve a quedar disponible.
