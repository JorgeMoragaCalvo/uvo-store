-- A5: JWTs were 24h, self-contained and irrevocable. Deactivating an admin, deleting them or
-- changing their role had no effect until the token expired on its own, because nothing after
-- login ever re-checked the principal — the filter trusted the token's claims and never read the
-- database.
--
-- The token now carries the version it was issued against; bumping this column invalidates every
-- token already out there for that principal, without any server-side session store.
-- DEFAULT 0 matters: tokens issued before this migration carry no version claim and are treated as
-- version 0, so deploying this doesn't log everyone out.
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE customers ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
