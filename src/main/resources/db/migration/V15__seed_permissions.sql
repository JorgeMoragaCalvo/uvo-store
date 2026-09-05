-- A1: the Role/Permission tables, their admin CRUD and the JWT authorities have existed since V4,
-- but the catalogue was never seeded — 0 permissions, 0 roles, 0 assignments — so there was nothing
-- to annotate endpoints with and `GET /api/admin/roles/permissions` answered an empty map. The
-- practical effect: every authenticated admin could do everything, whatever their role said.
--
-- Granularity is `view` / `manage` per domain: separating read from write is the actual finding
-- ("a read-only admin can delete products"), and it keeps the role screen to two checkboxes per
-- group. Finer splits later are just extra rows, no schema change.
--
-- Names use the `domain.action` shape RoleQueryServiceImpl.allPermissionsGrouped() already groups by
-- (prefix before the dot), so RoleForm renders these in labelled groups with no frontend change.

INSERT INTO permissions (name, guard_name, created_at, updated_at) VALUES
    ('products.view', 'web', now(), now()),
    ('products.manage', 'web', now(), now()),
    ('categories.view', 'web', now(), now()),
    ('categories.manage', 'web', now(), now()),
    ('orders.view', 'web', now(), now()),
    ('orders.manage', 'web', now(), now()),
    ('customers.view', 'web', now(), now()),
    ('customers.manage', 'web', now(), now()),
    ('coupons.view', 'web', now(), now()),
    ('coupons.manage', 'web', now(), now()),
    ('shipping.view', 'web', now(), now()),
    ('shipping.manage', 'web', now(), now()),
    ('users.view', 'web', now(), now()),
    ('users.manage', 'web', now(), now()),
    ('roles.view', 'web', now(), now()),
    ('roles.manage', 'web', now(), now()),
    ('payments.view', 'web', now(), now()),
    ('payments.manage', 'web', now(), now()),
    ('banners.view', 'web', now(), now()),
    ('banners.manage', 'web', now(), now()),
    ('settings.view', 'web', now(), now()),
    ('settings.manage', 'web', now(), now()),
    -- Reports are read-only by nature: there is nothing to manage.
    ('reports.view', 'web', now(), now());

-- One full-access role per existing store. Roles are per-store (V8 replaced the global
-- UNIQUE(name, guard_name) with UNIQUE(store_id, name, guard_name)); permissions stay global.
INSERT INTO roles (name, guard_name, store_id, created_at, updated_at)
SELECT 'Administrador', 'web', s.id, now(), now() FROM stores s;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.name = 'Administrador';

-- Without this, enforcing the annotations locks every existing admin out of their own panel: nobody
-- has a role yet, and from now on no role means no permissions.
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.store_id = u.store_id AND r.name = 'Administrador'
WHERE u.is_admin = true;
