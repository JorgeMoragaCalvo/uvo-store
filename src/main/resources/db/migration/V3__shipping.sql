CREATE TABLE shipping_zones (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR NOT NULL,
    description TEXT,
    regions JSONB,
    communes JSONB,
    is_active BOOLEAN NOT NULL,
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE shipping_methods (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR NOT NULL,
    code VARCHAR NOT NULL UNIQUE,
    description TEXT,
    type VARCHAR NOT NULL,
    has_api_integration BOOLEAN NOT NULL,
    api_credentials JSONB,
    min_delivery_days INTEGER,
    max_delivery_days INTEGER,
    is_active BOOLEAN NOT NULL,
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE shipping_rates (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR NOT NULL,
    rate_type VARCHAR NOT NULL,
    flat_rate NUMERIC(10,2),
    weight_rate_per_kg NUMERIC(10,2),
    base_weight_rate NUMERIC(10,2),
    min_order_amount NUMERIC(10,2),
    max_order_amount NUMERIC(10,2),
    min_weight NUMERIC(10,2),
    max_weight NUMERIC(10,2),
    free_shipping_threshold NUMERIC(10,2),
    is_active BOOLEAN NOT NULL,
    sort_order INTEGER NOT NULL,
    shipping_method_id BIGINT NOT NULL REFERENCES shipping_methods(id) ON DELETE CASCADE,
    shipping_zone_id BIGINT NOT NULL REFERENCES shipping_zones(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
