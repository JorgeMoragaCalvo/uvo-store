-- C5: stock and coupon usage were written with read-check-write everywhere (zero @Modifying, @Lock
-- or @Version in the whole backend), so two concurrent payments for the last unit both decremented
-- it and a single-use coupon could be redeemed twice. The application side now uses conditional
-- UPDATEs; this migration adds the database-level guarantees those depend on.

-- Last line of defence. The conditional UPDATEs carry `WHERE stock >= :quantity`, but the POS sync
-- and the admin CRUD still assign stock directly — if any of them ever computes a negative, the
-- engine rejects it instead of silently storing an impossible inventory.
ALTER TABLE products ADD CONSTRAINT products_stock_non_negative CHECK (stock >= 0);
ALTER TABLE product_variations ADD CONSTRAINT product_variations_stock_non_negative CHECK (stock >= 0);

-- One usage row per (coupon, order). Makes releasing a usage idempotent: the row's absence is the
-- proof it was already returned, so a repeated cancellation can't decrement times_used twice.
ALTER TABLE coupon_usages ADD CONSTRAINT coupon_usages_coupon_order_unique UNIQUE (coupon_id, order_id);

-- Stock is decremented on payment confirmation and restored on cancellation, and four different
-- code paths can cancel an order. Without these flags there is no way to know whether a given order
-- already had its stock applied or returned, so a second cancellation would invent inventory.
ALTER TABLE orders ADD COLUMN stock_applied BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN stock_restored BOOLEAN NOT NULL DEFAULT false;

-- Orders already paid before this migration did have their stock decremented by the old listener.
-- Without this backfill, cancelling one of them would restore stock that was never taken twice.
UPDATE orders SET stock_applied = true WHERE payment_status = 'PAID';
