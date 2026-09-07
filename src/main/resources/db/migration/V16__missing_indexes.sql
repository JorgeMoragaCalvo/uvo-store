-- M2: the multi-tenant retrofit (V8) indexed store_id everywhere and stopped there. Everything the
-- admin order listing actually filters and sorts by went unindexed, and three tables have no index
-- at all beyond their primary key.
--
-- These follow AdminOrderQueryServiceImpl.buildSpecification rather than guesswork: store_id is
-- always applied, then status or payment_status, and the default sort is created_at DESC.
-- Leading with store_id matters — it's the column every query has, so a composite starting there
-- serves both the filter and the sort in one index.
CREATE INDEX orders_store_id_created_at_idx ON orders (store_id, created_at DESC);
CREATE INDEX orders_store_id_status_idx ON orders (store_id, status);
CREATE INDEX orders_store_id_payment_status_idx ON orders (store_id, payment_status);

-- Foreign keys Postgres does NOT index automatically (unlike primary keys). Without these, loading
-- a customer's orders or cascading a delete means a sequential scan.
CREATE INDEX orders_customer_id_idx ON orders (customer_id);
CREATE INDEX orders_coupon_id_idx ON orders (coupon_id);

-- These three tables had no index whatsoever besides their PK, and every one of them is read by
-- order id on the order detail screen.
CREATE INDEX order_items_order_id_idx ON order_items (order_id);
CREATE INDEX order_items_product_id_idx ON order_items (product_id);
CREATE INDEX order_status_history_order_id_idx ON order_status_history (order_id);
CREATE INDEX coupon_usages_customer_id_idx ON coupon_usages (customer_id);

-- Deliberately NOT indexed: the text columns the admin search filters on (order_number,
-- customer_email, customer_first_name, customer_last_name). buildSpecification uses
-- LIKE '%term%', which a B-tree can't serve — it would need a trigram/GIN index, and nobody has
-- reported that search being slow.
