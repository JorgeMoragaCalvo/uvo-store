-- G4. PaymentStatus.REFUNDED y OrderStatus.REFUNDED existían en los enums desde el principio y no
-- había implementación detrás: el panel marcaba una orden como devuelta y no se movía un peso. Desde
-- este cambio, el reembolso pasa por la pasarela y queda registrado aquí.
--
-- Tabla y no una columna en orders porque un reembolso parcial es 1..N por orden: lo devuelto se suma
-- y no se denormaliza, así no puede quedar desfasado respecto de las filas que lo componen.
CREATE TABLE order_refunds (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    amount NUMERIC(10,2) NOT NULL,
    -- Lo que devuelve la pasarela: el id del refund de Stripe o MercadoPago, o el tipo de Transbank
    -- (REVERSED si es anulación del mismo día, NULLIFIED si es reembolso). Nulo en los externos.
    gateway_reference VARCHAR,
    type VARCHAR NOT NULL,
    reason TEXT,
    -- Quién lo hizo. No hay audit log todavía (G6) y devolver dinero es justo lo que hay que poder
    -- atribuir; ON DELETE SET NULL para no perder el reembolso si el usuario se borra.
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- EXTERNAL es el reembolso que alguien ya hizo desde el panel de la pasarela y que aquí solo se
    -- registra; por eso es el único que no lleva referencia de pasarela obligatoria.
    CONSTRAINT order_refunds_type_valid CHECK (type IN ('FULL', 'PARTIAL', 'EXTERNAL')),
    -- Un reembolso de cero o negativo no es un reembolso.
    CONSTRAINT order_refunds_amount_positive CHECK (amount > 0)
);

-- Se consulta siempre por orden, para sumar lo ya devuelto antes de autorizar el siguiente.
CREATE INDEX idx_order_refunds_order_id ON order_refunds(order_id);

-- Devolver dinero merece permiso propio, separado de cambiar un estado: 'orders.manage' lo tiene
-- cualquiera que despache pedidos.
INSERT INTO permissions (name, guard_name, created_at, updated_at) VALUES
    ('orders.refund', 'web', now(), now());

-- Y se concede a los roles que ya pueden gestionar órdenes. Sin esto, el día que esto se despliegue
-- nadie —ni el rol Administrador que creó V15— podría reembolsar, porque el permiso es nuevo y no
-- está en ninguna asignación existente.
INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, (SELECT id FROM permissions WHERE name = 'orders.refund')
FROM role_permissions rp
JOIN permissions p ON p.id = rp.permission_id
WHERE p.name = 'orders.manage';
