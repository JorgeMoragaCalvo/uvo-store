-- Fase 2: per-store payment gateway configuration (Stripe/Webpay/MercadoPago), credentials
-- encrypted at rest by the application (EncryptedCredentialsConverter), stored as ciphertext in
-- a plain TEXT column — not JSONB, since the column never holds valid JSON.
CREATE TABLE payment_gateway_configs (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id),
    gateway VARCHAR NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    credentials TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT payment_gateway_configs_store_id_gateway_key UNIQUE (store_id, gateway)
);

CREATE INDEX payment_gateway_configs_store_id_idx ON payment_gateway_configs (store_id);
