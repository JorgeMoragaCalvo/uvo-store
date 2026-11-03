-- GAP-05. El IVA se calculaba sobre el subtotal antes del cupón y no se recalculaba después del descuento: con precios
-- sin IVA el comprador pagaba IVA sobre un importe que no pagó (10.000 con cupón de 1.000 cobraba 10.900 en vez de
-- 10.710), y con precios con IVA la orden y el documento POS declaraban IVA de más. Además, cada línea guardaba
-- tax_amount = 0, así que las líneas no sumaban la orden.
--
-- Desde ahora:
--   orders.tax_included   true cuando el subtotal y el descuento de la orden van con IVA (precios con IVA incluido)
--                         y el IVA está contenido: subtotal - descuento + envío = total. false cuando el IVA se suma:
--                         subtotal - descuento + IVA + envío = total.
--   order_items.discount_amount  la parte del descuento de la orden que le toca a la línea. Junto con tax_amount, que
--                         ahora sí se rellena, las líneas suman el descuento y el IVA de la orden.
ALTER TABLE orders ADD COLUMN tax_included boolean NOT NULL DEFAULT false;
ALTER TABLE order_items ADD COLUMN discount_amount numeric(10,2) NOT NULL DEFAULT 0;

-- Las órdenes anteriores no se recalculan: ya se cobraron y su documento ya se emitió con el criterio de entonces.
-- Todas cumplen subtotal - descuento + IVA + envío = total, incluso las de precios con IVA incluido, que guardaban el
-- subtotal sin IVA y el IVA de antes del descuento. Por eso tax_included = false describe bien cómo suman.
