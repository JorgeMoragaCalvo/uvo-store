-- F04: el descuento de stock se decide línea a línea (cada uno es su propio UPDATE condicional
-- `WHERE stock >= :quantity`), pero hasta ahora solo se anotaba a nivel de orden. Una línea que no se
-- podía descontar dejaba la orden marcada como aplicada igualmente, y la cancelación devolvía TODAS
-- las líneas: unidades que nunca se descontaron aparecían en el inventario.
--
-- Caso sin ninguna concurrencia (F10): dos líneas del mismo producto con stock 1 pasan las dos la
-- validación del carrito, la primera descuenta y la segunda falla. Cancelar devolvía 2 por 1.
ALTER TABLE order_items ADD COLUMN stock_applied BOOLEAN NOT NULL DEFAULT false;

-- El backfill simétrico al de V13, y por el mismo motivo pero al revés. Allí se rellenó
-- orders.stock_applied para no restaurar dos veces lo de las órdenes ya pagadas; aquí hay que
-- rellenar las líneas de esas mismas órdenes, porque si se quedan en false una cancelación posterior
-- no devolvería NADA y el fallo se invertiría: en vez de inventar unidades, las perdería.
--
-- Se asume que en esas órdenes históricas se descontaron todas las líneas. No es exacto —alguna pudo
-- fallar, es justamente lo que este cambio arregla— pero es la única lectura disponible, y errar así
-- deja el inventario como lo dejaba el código anterior en vez de peor.
UPDATE order_items SET stock_applied = true
WHERE order_id IN (SELECT id FROM orders WHERE stock_applied = true);
