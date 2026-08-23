-- Multi-tenancy retrofit (Fase 0): every tenant-scoped table gets a store_id FK.
-- Existing data is backfilled onto the seed store (id=1) so nothing already tested is lost.
-- Child tables (order_items, order_status_history, coupon_usages, product_sync_mapping,
-- sync_webhook_logs) intentionally do NOT get their own store_id — they inherit tenant scope
-- via their parent FK (order_id / product_id / etc.), same convention already used for those
-- relationships pre-migration.

CREATE TABLE stores (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR NOT NULL,
    slug VARCHAR NOT NULL UNIQUE,
    owner_user_id BIGINT,
    status VARCHAR NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

INSERT INTO stores (id, name, slug, status, created_at, updated_at)
VALUES (1, 'Tienda Demo', 'demo', 'active', now(), now());

SELECT setval(pg_get_serial_sequence('stores', 'id'), 1, true);

-- categories --------------------------------------------------------------
ALTER TABLE categories ADD COLUMN store_id BIGINT;
UPDATE categories SET store_id = 1;
ALTER TABLE categories ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE categories ADD CONSTRAINT categories_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE categories DROP CONSTRAINT categories_slug_key;
ALTER TABLE categories ADD CONSTRAINT categories_store_id_slug_key UNIQUE (store_id, slug);
CREATE INDEX categories_store_id_idx ON categories (store_id);

-- attributes ----------------------------------------------------------------
ALTER TABLE attributes ADD COLUMN store_id BIGINT;
UPDATE attributes SET store_id = 1;
ALTER TABLE attributes ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE attributes ADD CONSTRAINT attributes_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE attributes DROP CONSTRAINT attributes_slug_key;
ALTER TABLE attributes ADD CONSTRAINT attributes_store_id_slug_key UNIQUE (store_id, slug);
CREATE INDEX attributes_store_id_idx ON attributes (store_id);

-- attribute_values ------------------------------------------------------------
ALTER TABLE attribute_values ADD COLUMN store_id BIGINT;
UPDATE attribute_values SET store_id = 1;
ALTER TABLE attribute_values ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE attribute_values ADD CONSTRAINT attribute_values_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
CREATE INDEX attribute_values_store_id_idx ON attribute_values (store_id);

-- products --------------------------------------------------------------
ALTER TABLE products ADD COLUMN store_id BIGINT;
UPDATE products SET store_id = 1;
ALTER TABLE products ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE products ADD CONSTRAINT products_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE products DROP CONSTRAINT products_slug_key;
ALTER TABLE products ADD CONSTRAINT products_store_id_slug_key UNIQUE (store_id, slug);
ALTER TABLE products DROP CONSTRAINT products_sku_key;
ALTER TABLE products ADD CONSTRAINT products_store_id_sku_key UNIQUE (store_id, sku);
CREATE INDEX products_store_id_idx ON products (store_id);

-- product_images --------------------------------------------------------------
ALTER TABLE product_images ADD COLUMN store_id BIGINT;
UPDATE product_images SET store_id = 1;
ALTER TABLE product_images ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE product_images ADD CONSTRAINT product_images_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
CREATE INDEX product_images_store_id_idx ON product_images (store_id);

-- product_variations --------------------------------------------------------------
ALTER TABLE product_variations ADD COLUMN store_id BIGINT;
UPDATE product_variations SET store_id = 1;
ALTER TABLE product_variations ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE product_variations ADD CONSTRAINT product_variations_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE product_variations DROP CONSTRAINT product_variations_sku_key;
ALTER TABLE product_variations ADD CONSTRAINT product_variations_store_id_sku_key UNIQUE (store_id, sku);
CREATE INDEX product_variations_store_id_idx ON product_variations (store_id);

-- product_variation_attributes -----------------------------------------------
ALTER TABLE product_variation_attributes ADD COLUMN store_id BIGINT;
UPDATE product_variation_attributes SET store_id = 1;
ALTER TABLE product_variation_attributes ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE product_variation_attributes ADD CONSTRAINT pva_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
CREATE INDEX pva_store_id_idx ON product_variation_attributes (store_id);

-- customers --------------------------------------------------------------
ALTER TABLE customers ADD COLUMN store_id BIGINT;
UPDATE customers SET store_id = 1;
ALTER TABLE customers ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE customers ADD CONSTRAINT customers_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE customers DROP CONSTRAINT customers_email_key;
ALTER TABLE customers ADD CONSTRAINT customers_store_id_email_key UNIQUE (store_id, email);
CREATE INDEX customers_store_id_idx ON customers (store_id);

-- shipping_addresses --------------------------------------------------------------
ALTER TABLE shipping_addresses ADD COLUMN store_id BIGINT;
UPDATE shipping_addresses SET store_id = 1;
ALTER TABLE shipping_addresses ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE shipping_addresses ADD CONSTRAINT shipping_addresses_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
CREATE INDEX shipping_addresses_store_id_idx ON shipping_addresses (store_id);

-- shipping_zones --------------------------------------------------------------
ALTER TABLE shipping_zones ADD COLUMN store_id BIGINT;
UPDATE shipping_zones SET store_id = 1;
ALTER TABLE shipping_zones ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE shipping_zones ADD CONSTRAINT shipping_zones_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
CREATE INDEX shipping_zones_store_id_idx ON shipping_zones (store_id);

-- shipping_methods --------------------------------------------------------------
ALTER TABLE shipping_methods ADD COLUMN store_id BIGINT;
UPDATE shipping_methods SET store_id = 1;
ALTER TABLE shipping_methods ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE shipping_methods ADD CONSTRAINT shipping_methods_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE shipping_methods DROP CONSTRAINT shipping_methods_code_key;
ALTER TABLE shipping_methods ADD CONSTRAINT shipping_methods_store_id_code_key UNIQUE (store_id, code);
CREATE INDEX shipping_methods_store_id_idx ON shipping_methods (store_id);

-- shipping_rates --------------------------------------------------------------
ALTER TABLE shipping_rates ADD COLUMN store_id BIGINT;
UPDATE shipping_rates SET store_id = 1;
ALTER TABLE shipping_rates ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE shipping_rates ADD CONSTRAINT shipping_rates_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
CREATE INDEX shipping_rates_store_id_idx ON shipping_rates (store_id);

-- orders --------------------------------------------------------------
-- order_number stays globally unique on purpose (payment-gateway/webhook lookups reference it
-- without store context; the random suffix already makes cross-store collision negligible).
ALTER TABLE orders ADD COLUMN store_id BIGINT;
UPDATE orders SET store_id = 1;
ALTER TABLE orders ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE orders ADD CONSTRAINT orders_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
CREATE INDEX orders_store_id_idx ON orders (store_id);

-- coupons --------------------------------------------------------------
ALTER TABLE coupons ADD COLUMN store_id BIGINT;
UPDATE coupons SET store_id = 1;
ALTER TABLE coupons ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE coupons ADD CONSTRAINT coupons_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE coupons DROP CONSTRAINT coupons_code_key;
ALTER TABLE coupons ADD CONSTRAINT coupons_store_id_code_key UNIQUE (store_id, code);
CREATE INDEX coupons_store_id_idx ON coupons (store_id);

-- pos_connections --------------------------------------------------------------
-- company_id stays globally unique: it's UVO POS's own external identifier, not ours.
ALTER TABLE pos_connections ADD COLUMN store_id BIGINT;
UPDATE pos_connections SET store_id = 1;
ALTER TABLE pos_connections ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE pos_connections ADD CONSTRAINT pos_connections_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
CREATE INDEX pos_connections_store_id_idx ON pos_connections (store_id);

-- store_settings --------------------------------------------------------------
-- Was a service-enforced singleton; becomes one row per store (DB-enforced via UNIQUE now).
ALTER TABLE store_settings ADD COLUMN store_id BIGINT;
UPDATE store_settings SET store_id = 1;
ALTER TABLE store_settings ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE store_settings ADD CONSTRAINT store_settings_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE store_settings ADD CONSTRAINT store_settings_store_id_key UNIQUE (store_id);

-- settings --------------------------------------------------------------
ALTER TABLE settings ADD COLUMN store_id BIGINT;
UPDATE settings SET store_id = 1;
ALTER TABLE settings ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE settings ADD CONSTRAINT settings_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE settings DROP CONSTRAINT settings_key_key;
ALTER TABLE settings ADD CONSTRAINT settings_store_id_key_key UNIQUE (store_id, key);
CREATE INDEX settings_store_id_idx ON settings (store_id);

-- home_banners --------------------------------------------------------------
ALTER TABLE home_banners ADD COLUMN store_id BIGINT;
UPDATE home_banners SET store_id = 1;
ALTER TABLE home_banners ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE home_banners ADD CONSTRAINT home_banners_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
CREATE INDEX home_banners_store_id_idx ON home_banners (store_id);

-- users --------------------------------------------------------------
-- Each admin/staff account belongs to exactly one store (no cross-store platform-superadmin
-- concept yet — out of scope, see plan).
ALTER TABLE users ADD COLUMN store_id BIGINT;
UPDATE users SET store_id = 1;
ALTER TABLE users ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE users DROP CONSTRAINT users_email_key;
ALTER TABLE users ADD CONSTRAINT users_store_id_email_key UNIQUE (store_id, email);
CREATE INDEX users_store_id_idx ON users (store_id);

-- roles --------------------------------------------------------------
-- permissions stays a global capability catalog (shared across stores) — only roles (which
-- roles exist, and which permissions each grants) is per-store.
ALTER TABLE roles ADD COLUMN store_id BIGINT;
UPDATE roles SET store_id = 1;
ALTER TABLE roles ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE roles ADD CONSTRAINT roles_store_id_fkey FOREIGN KEY (store_id) REFERENCES stores(id);
ALTER TABLE roles DROP CONSTRAINT roles_name_guard_name_key;
ALTER TABLE roles ADD CONSTRAINT roles_store_id_name_guard_name_key UNIQUE (store_id, name, guard_name);
CREATE INDEX roles_store_id_idx ON roles (store_id);

-- Now that a real users(store_id) row exists, back-fill stores.owner_user_id and enforce the FK.
UPDATE stores SET owner_user_id = (SELECT id FROM users ORDER BY id ASC LIMIT 1) WHERE id = 1;
ALTER TABLE stores ADD CONSTRAINT stores_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES users(id);
