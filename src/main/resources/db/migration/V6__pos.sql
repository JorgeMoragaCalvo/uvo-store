CREATE TABLE pos_connections (
    id BIGSERIAL PRIMARY KEY,
    company_name VARCHAR NOT NULL,
    company_id BIGINT NOT NULL UNIQUE,
    api_url VARCHAR NOT NULL,
    api_key VARCHAR NOT NULL,
    webhook_secret VARCHAR NOT NULL,
    is_active BOOLEAN NOT NULL,
    connected_at TIMESTAMPTZ,
    last_ping_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

-- company_id here is a plain value, not a DB-level FK to pos_connections
-- (the application joins on it by value only — see ProductSyncMapping javadoc).
CREATE TABLE product_sync_mapping (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    external_id BIGINT NOT NULL,
    external_sku VARCHAR(100) NOT NULL,
    company_id BIGINT NOT NULL,
    warehouse_id BIGINT,
    sync_status VARCHAR NOT NULL,
    sync_direction VARCHAR NOT NULL,
    sync_error TEXT,
    last_synced_at TIMESTAMPTZ,
    sync_stock BOOLEAN NOT NULL,
    sync_price BOOLEAN NOT NULL,
    sync_name BOOLEAN NOT NULL,
    sync_description BOOLEAN NOT NULL,
    sync_metadata JSONB,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT unique_external_product UNIQUE (external_id, company_id),
    CONSTRAINT unique_store_product UNIQUE (product_id, company_id)
);

CREATE TABLE sync_webhook_logs (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    status VARCHAR NOT NULL,
    payload JSONB,
    error_message TEXT,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
