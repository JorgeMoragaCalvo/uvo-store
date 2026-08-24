-- Fase 3: quote-only carrier integrations (Chilexpress/Correos de Chile) on top of the existing
-- ShippingMethod/ShippingRate model. A method with hasApiIntegration=true and a non-null carrier
-- asks that carrier for a live rate at checkout instead of reading the static shipping_rates table.
ALTER TABLE shipping_methods ADD COLUMN carrier VARCHAR;

-- api_credentials was declared jsonb but never actually encrypted despite its own comment saying
-- it should be — no rows use it yet, so this corrects the column to what
-- EncryptedCredentialsConverter needs (ciphertext text, same converter payment_gateway_configs
-- uses) before any carrier credentials are ever written to it.
ALTER TABLE shipping_methods ALTER COLUMN api_credentials TYPE text USING NULL::text;
