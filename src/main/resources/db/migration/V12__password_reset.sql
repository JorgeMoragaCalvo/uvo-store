-- Password recovery for admin users and customers. Deliberately separate from
-- invitation_token (one-time, set at account creation) — reset tokens are reissued and expire on
-- every "forgot password" request, so they need their own lifecycle columns.
ALTER TABLE users ADD COLUMN password_reset_token VARCHAR UNIQUE;
ALTER TABLE users ADD COLUMN password_reset_expires_at TIMESTAMP;

ALTER TABLE customers ADD COLUMN password_reset_token VARCHAR UNIQUE;
ALTER TABLE customers ADD COLUMN password_reset_expires_at TIMESTAMP;
