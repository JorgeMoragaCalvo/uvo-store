-- Store onboarding: a store's public-facing identity can now be its own custom domain
-- (client-provided, e.g. "tiendadejuan.cl"), resolved by TenantResolutionFilter ahead of the
-- existing subdomain-slug scheme. Nullable — the domain may not be known/ready at onboarding
-- time, and the slug-based subdomain remains a working fallback for every store regardless.
ALTER TABLE stores ADD COLUMN domain VARCHAR UNIQUE;
