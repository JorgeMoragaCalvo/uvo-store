-- G5. V17 dejó fuera stores.status a propósito: parecía columna de enum pero no lo era —era un
-- String libre inicializado a 'active' en minúscula— y ponerle un CHECK exigía inventar su dominio.
-- Ahora ese dominio existe (StoreStatus: ACTIVE, SUSPENDED) porque hay código que lo lee: desde este
-- cambio, TenantResolutionFilter rechaza con 403 las peticiones a una tienda SUSPENDED.
--
-- El UPPER va antes que el CHECK y no al revés: las filas existentes dicen 'active' en minúscula y
-- la restricción las rechazaría. El DEFAULT también se normaliza; si no, un INSERT que omita la
-- columna escribiría 'active' y violaría el CHECK que acabamos de añadir.
UPDATE stores SET status = UPPER(status);

ALTER TABLE stores ALTER COLUMN status SET DEFAULT 'ACTIVE';

ALTER TABLE stores ADD CONSTRAINT stores_status_valid
    CHECK (status IN ('ACTIVE', 'SUSPENDED'));
