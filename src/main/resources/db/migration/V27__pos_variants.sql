-- POS-5. Las variantes de un producto del POS (talla, color), cada una con su id, su precio y su stock.
--
-- product_sync_mapping sigue siendo el mapeo del PRODUCTO: la empresa, los flags y la versión de catálogo, que
-- cubre también sus variantes (cualquier cambio en una variante sube la versión del producto en el POS). Esta
-- tabla solo dice qué variación de la web es cada variante del POS.
--
-- Cuelga del mapeo y no de la empresa: un producto mapeado a dos empresas (PROD-13b) tiene un juego de
-- variantes por cada una, con los ids de cada POS. La empresa es la del mapeo padre.
CREATE TABLE product_variation_sync_mapping (
    id BIGSERIAL PRIMARY KEY,
    mapping_id BIGINT NOT NULL REFERENCES product_sync_mapping(id) ON DELETE CASCADE,
    product_variation_id BIGINT NOT NULL REFERENCES product_variations(id) ON DELETE CASCADE,
    external_variant_id BIGINT NOT NULL,
    external_sku VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT pvsm_external_variant_unique UNIQUE (mapping_id, external_variant_id),
    CONSTRAINT pvsm_variation_unique UNIQUE (mapping_id, product_variation_id)
);

-- La conciliación y la venta buscan por variación.
CREATE INDEX idx_pvsm_product_variation_id ON product_variation_sync_mapping (product_variation_id);

-- REVISIÓN MANUAL:
--
--   Las variantes del POS de un producto y su estado en la web:
--     SELECT m.external_id, vm.external_variant_id, v.id AS variation_id, v.sku, v.price, v.stock, v.active
--     FROM product_sync_mapping m
--     JOIN product_variation_sync_mapping vm ON vm.mapping_id = m.id
--     JOIN product_variations v ON v.id = vm.product_variation_id
--     WHERE m.external_id = <id del POS> AND m.company_id = <empresa>
--     ORDER BY vm.external_variant_id;
--
--   Lo que se rechazó por no cuadrar el tipo (simple en la web, con variantes en el POS, o al revés):
--     SELECT id, company_id, event_type, error_message, created_at
--     FROM sync_webhook_logs WHERE status = 'FAILED' AND error_message LIKE '%variantes%' ORDER BY id DESC;
