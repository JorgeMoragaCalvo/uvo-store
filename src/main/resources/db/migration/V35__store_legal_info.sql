-- GAP-18. Las paginas legales del storefront tenian texto fijo, igual para todas las tiendas, y ninguna identificaba
-- al vendedor. Desde ahora toman de aqui lo que varia entre tiendas:
--   legal_name                 razon social del vendedor.
--   legal_tax_id               RUT del vendedor, normalizado sin puntos y con guion (12345678-5), DV en mayuscula.
--                              Lo valida ChileanRut al guardar; el CHECK es el cinturon para lo que no pase por ahi.
--   legal_address              domicilio del vendedor.
--   withdrawal_right_excluded  true si la tienda excluye el derecho a retracto (Ley 19.496, art. 3 bis b).
--                              Se guarda en negativo a proposito: el PUT de la configuracion reemplaza todos los
--                              campos, y un booleano que no llega vale false. Asi, un cliente que no lo mande deja
--                              el retracto vigente, que es lo que fija la ley, en vez de apagarlo en silencio.
ALTER TABLE store_settings ADD COLUMN legal_name varchar(255);
ALTER TABLE store_settings ADD COLUMN legal_tax_id varchar(12);
ALTER TABLE store_settings ADD COLUMN legal_address varchar(500);
ALTER TABLE store_settings ADD COLUMN withdrawal_right_excluded boolean NOT NULL DEFAULT false;

ALTER TABLE store_settings ADD CONSTRAINT chk_store_settings_legal_tax_id
    CHECK (legal_tax_id IS NULL OR legal_tax_id ~ '^[0-9]{7,8}-[0-9K]$');
