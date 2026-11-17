-- IMP-05 (C07). La base y los dos roles de UvoStore en un ambiente, en el PostgreSQL de la plataforma UvoPOS.
--
-- Lo aplica ops/servidor/provisionar.sh como superusuario (sudo -u postgres psql), con las variables por la
-- entrada estándar y no por -v: así las contraseñas no aparecen en la lista de procesos. A mano:
--
--   sudo -u postgres psql -X -v ON_ERROR_STOP=1 <<'FIN'
--   \set ambiente staging
--   \set clave_migrador '...'
--   \set clave_app '...'
--   \set limite_app 12
--   \i ops/postgres/roles.sql
--   FIN
--
-- Idempotente: volver a correrlo no rompe nada y deja las contraseñas que se le pasen, así que también es como
-- se rotan (después, actualizar /etc/uvostore/<ambiente>.env y reiniciar el servicio).
--
-- Queda así:
--   uvostore_<ambiente>             base propia; nadie más se conecta (REVOKE ... FROM PUBLIC)
--   uvostore_<ambiente>_migrador    dueño de la base y del esquema public; lo usa solo Flyway (FLYWAY_USER)
--   uvostore_<ambiente>_app         CONNECT, USAGE y DML; sin CREATE ni tablas propias (DB_USERNAME). Es lo que
--                                   DatabaseRole comprueba al arrancar fuera de development.
-- Staging y production tienen bases y roles distintos: un rol de un ambiente no puede ni conectarse a la base
-- del otro. Las migraciones no necesitan superusuario (ninguna crea extensiones).

\set ON_ERROR_STOP on

SELECT :'ambiente' IN ('staging', 'production') AS ambiente_valido \gset
\if :ambiente_valido
\else
    -- Un error y no \quit, que saldría con estado 0 y el script que lo llama seguiría como si nada.
    DO $$ BEGIN RAISE EXCEPTION 'ambiente tiene que ser staging o production'; END $$;
\endif

\set base uvostore_ :ambiente
\set migrador uvostore_ :ambiente _migrador
\set app uvostore_ :ambiente _app
\if :{?limite_app}
\else
    \set limite_app 12
\endif

-- Roles. CREATE solo si falta; ALTER siempre, para dejar atributos y contraseña como se pidieron.
SELECT format('CREATE ROLE %I LOGIN', :'migrador')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrador') \gexec
SELECT format('CREATE ROLE %I LOGIN', :'app')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app') \gexec

-- El migrador solo abre una conexión al arrancar el servicio; 2 deja margen para una consola de emergencia.
ALTER ROLE :"migrador" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    CONNECTION LIMIT 2 PASSWORD :'clave_migrador';
ALTER ROLE :"app" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    CONNECTION LIMIT :limite_app PASSWORD :'clave_app';

-- Base. CREATE DATABASE no puede ir en una transacción ni en un bloque DO; \gexec la manda sola.
SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L TEMPLATE template0', :'base', :'migrador', 'UTF8')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'base') \gexec

ALTER DATABASE :"base" OWNER TO :"migrador";
REVOKE ALL ON DATABASE :"base" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"base" TO :"app";

\connect :"base"

-- Desde PostgreSQL 15 nadie más que el dueño crea en public, pero en versiones anteriores PUBLIC sí podía.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO :"migrador";
GRANT USAGE ON SCHEMA public TO :"app";

-- Lo que ya exista (una base que se migró antes de este script) y lo que creen las migraciones siguientes.
-- Si las tablas existentes son de otro rol, hay que pasarlas antes al migrador (ALTER TABLE ... OWNER TO).
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"app";
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO :"app";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrador" IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrador" IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"app";

-- La misma comprobación que hace DatabaseRole al arrancar, para verla aquí y no recién en el log del servicio.
SELECT r.rolname                                                    AS rol,
       r.rolsuper                                                   AS superusuario,
       has_schema_privilege(r.rolname, 'public', 'CREATE')          AS crea_en_public,
       (SELECT count(*) FROM pg_tables t
         WHERE t.schemaname = 'public' AND t.tableowner = r.rolname) AS tablas_propias,
       r.rolconnlimit                                               AS limite_conexiones
FROM pg_roles r
WHERE r.rolname IN (:'migrador', :'app')
ORDER BY r.rolname;
