-- B5 + B6: dos arreglos de integridad del esquema que van juntos porque ambos consisten en decirle
-- a la base lo que hasta ahora solo sabía Java.

-- ---------------------------------------------------------------------------------------------
-- B5. Las dos únicas columnas de fecha sin zona horaria en todo el esquema. V12 las creó como
-- TIMESTAMP mientras el resto del proyecto usa TIMESTAMPTZ, así que su valor dependía de la zona
-- por defecto de la JVM: el mismo Instant escrito desde un servidor en UTC y leído desde uno en
-- America/Santiago da una expiración corrida tres o cuatro horas. En un token de recuperación de
-- contraseña eso significa una ventana más larga o más corta de lo que dice el código.
--
-- El AT TIME ZONE 'UTC' es la mitad que importa: sin él Postgres reinterpretaría los valores
-- existentes en la zona de la sesión que ejecute la migración. Hoy las dos columnas tienen cero
-- filas no nulas, así que da igual — pero esta migración también corre sobre bases que sí tengan
-- datos, y ahí no daría igual.
--
-- La otra mitad vive en application.properties: hibernate.jdbc.time_zone=UTC (B7), para que lo que
-- se escriba desde ahora tampoco dependa de la zona de la máquina.
ALTER TABLE users
    ALTER COLUMN password_reset_expires_at TYPE TIMESTAMPTZ
        USING password_reset_expires_at AT TIME ZONE 'UTC';

ALTER TABLE customers
    ALTER COLUMN password_reset_expires_at TYPE TIMESTAMPTZ
        USING password_reset_expires_at AT TIME ZONE 'UTC';

-- ---------------------------------------------------------------------------------------------
-- B6. Doce columnas que guardan el nombre de un enum como texto, sin nada que impida escribir
-- cualquier otra cosa. Mientras el único escritor sea Hibernate con @Enumerated(STRING) el problema
-- es teórico; deja de serlo con una migración de datos, una carga masiva, un `UPDATE` a mano en
-- producción o la sincronización del POS. Y el día que aparezca un valor inválido, el error no sale
-- al escribirlo sino al leerlo, con un IllegalArgumentException de Enum.valueOf en mitad de una
-- consulta que no tiene nada que ver.
--
-- Los valores salen de leer cada enum en entity/**/enums/, no de memoria, y se comprobó con un
-- SELECT DISTINCT que todo lo que hay hoy en la base cabe dentro. Un CHECK no restringe NULL, así
-- que las columnas opcionales siguen aceptándolo.

ALTER TABLE orders ADD CONSTRAINT orders_status_valid
    CHECK (status IN ('PENDING', 'PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED'));
ALTER TABLE orders ADD CONSTRAINT orders_payment_status_valid
    CHECK (payment_status IN ('PENDING', 'PAID', 'FAILED', 'REFUNDED'));
ALTER TABLE orders ADD CONSTRAINT orders_fulfillment_status_valid
    CHECK (fulfillment_status IN ('UNFULFILLED', 'PARTIAL', 'FULFILLED'));

-- El caso que más lo necesita: OrderStatusHistory.status es un String en Java, no un enum, con un
-- comentario pidiendo que se usen los valores de OrderStatus. Aquí eso pasa de ser una petición a
-- ser una restricción.
ALTER TABLE order_status_history ADD CONSTRAINT order_status_history_status_valid
    CHECK (status IN ('PENDING', 'PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED'));

ALTER TABLE products ADD CONSTRAINT products_product_type_valid
    CHECK (product_type IN ('SIMPLE', 'VARIABLE'));
ALTER TABLE product_images ADD CONSTRAINT product_images_type_valid
    CHECK (type IN ('GALLERY', 'THUMBNAIL', 'HERO'));
ALTER TABLE attributes ADD CONSTRAINT attributes_type_valid
    CHECK (type IN ('SELECT', 'SWATCH', 'BUTTON'));

ALTER TABLE coupons ADD CONSTRAINT coupons_type_valid
    CHECK (type IN ('PERCENTAGE', 'FIXED'));
ALTER TABLE customers ADD CONSTRAINT customers_account_status_valid
    CHECK (account_status IN ('GUEST', 'INVITED', 'ACTIVE'));
ALTER TABLE shipping_methods ADD CONSTRAINT shipping_methods_type_valid
    CHECK (type IN ('COURIER', 'PICKUP', 'CUSTOM'));

ALTER TABLE product_sync_mapping ADD CONSTRAINT product_sync_mapping_sync_status_valid
    CHECK (sync_status IN ('ACTIVE', 'PAUSED', 'ERROR'));
ALTER TABLE sync_webhook_logs ADD CONSTRAINT sync_webhook_logs_status_valid
    CHECK (status IN ('RECEIVED', 'PROCESSING', 'SUCCESS', 'FAILED'));

-- Deliberadamente SIN restricción: stores.status. Parece una columna de enum pero no lo es —
-- Store.java:53 la declara `private String status = "active"`, en minúscula y sin enum detrás.
-- Ponerle un CHECK exigiría inventar su dominio, y eso es una decisión de diseño, no la corrección
-- de un hallazgo.
