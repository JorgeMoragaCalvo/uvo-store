# ADR 0001 · UvoStore en el servidor de la plataforma UvoPOS

- **Estado:** aceptada (IMP-05, 16-nov-2026). Formaliza la decisión de hosting del 13-oct-2026 (`PLAN_PRODUCCION.md` §4.1) y las de `docs/pos-ecommerce-communication.md` («Ola 5 ajustada»).
- **Alcance:** staging y production.
- **Revisar si:** cambia el proveedor, hace falta más de una instancia, o el servidor de UvoPOS se queda sin margen de memoria, CPU o conexiones.

## Contexto

UvoStore no tenía infraestructura propia ni decidida por escrito (GL-02). UvoPOS ya opera un VPS Ubuntu con:
- AlfozApi como jar en systemd, en `127.0.0.1:8080`;
- nginx con Let's Encrypt;
- PostgreSQL local;
- S3.

El acuerdo es compartir esa plataforma, pero con proceso, base, rol y secretos propios. La integración con el POS es por HTTP sobre la red local.

## Decisión

| Pieza | Decisión | Responsable |
|---|---|---|
| Proceso | Jar de Spring Boot como servicio systemd `uvostore-<ambiente>`, **una instancia** por ambiente, usuario de sistema propio. Límites `MemoryMax`/`CPUQuota` para no ahogar a AlfozApi. Apagado ordenado de 30 s por fase, con `TimeoutStopSec=60`. | Operaciones de la plataforma |
| Red | El backend escucha solo en `127.0.0.1`: staging en el 8081, production en el 8082. Nunca el 8080. La base, el puerto del backend y `/api/platform` no se publican. | Operaciones |
| Proxy | El nginx compartido, con `ops/nginx/` sin cambios en las locations. Sale hacia el backend desde `127.0.0.2` (`proxy_bind`), que es la única IP en `RATE_LIMIT_TRUSTED_PROXIES`. El POS llama a `/api/platform` desde `127.0.0.1`, directo. `default.conf` corta los hosts que ningún server tiene configurados. | Operaciones |
| PostgreSQL | El servidor del POS, con una base por ambiente (`uvostore_<ambiente>`) y dos roles: `_migrador`, dueño del esquema y usado solo por Flyway, y `_app`, solo DML. Cada uno con `CONNECTION LIMIT` (pool + 2 para la app). Nadie más se conecta a esas bases. | Operaciones + backend |
| Imágenes | S3, con un bucket por ambiente, lectura pública de objetos y un usuario IAM por ambiente con `PutObject`/`DeleteObject` sobre su bucket (`ops/s3/`). Sobreviven a reinicios, redespliegues y a perder el VPS. | Operaciones |
| Secretos | `/etc/uvostore/<ambiente>.env`, `root:uvostore-<ambiente>` y 640, como `EnvironmentFile`. Generados en el servidor por `provisionar.sh`, propios de cada ambiente. Copia de `APP_ENCRYPTION_KEY` en el gestor de secretos de la plataforma. | Operaciones + seguridad |
| Dominios y TLS | Un dominio de plataforma por ambiente (`<slug>.<dominio>`), con certificado comodín por DNS-01. Cada dominio propio con su certificado HTTP-01 (`dominio-propio.sh`). Renovación con el timer de Certbot y un hook que recarga nginx. | Operaciones |
| Artefactos | Lo que publica `release.yml`: el jar, el zip del frontend por ambiente, `uvostore-ops-<tag>.tar.gz` y `SHA256SUMS`. El servidor provisiona y despliega con los scripts del mismo tag. | Backend + operaciones |
| Staging y production | Mismo servidor y mismas plantillas. Ningún nombre, puerto, base, rol, secreto, bucket ni dominio compartido. | Operaciones |

## Consecuencias

- **Reproducible:** el ambiente se arma solo con `ops/` y los artefactos del release (`docs/despliegue-backend.md`). El job `ensayo-servidor` de CI lo construye desde cero en cada push y deja el informe de `verificar.sh`.
- **Despliegue con corte:** una instancia implica un corte de segundos en cada despliegue. Dos versiones atendiendo a la vez no son seguras con el outbox (`V25__outbox.sql`); varias réplicas requieren un diseño aparte (PROD-26).
- **Riesgo compartido:** una caída del VPS deja abajo al POS y a la tienda a la vez. Respaldo y restauración son IMP-09.
- **Pendiente fuera del repositorio:**
  - el proveedor DNS y su plugin de Certbot para el comodín;
  - los dominios reales de cada ambiente;
  - los buckets y usuarios IAM;
  - el SMTP (IMP-06);
  - la red desde donde recolecta Prometheus (IMP-03).
  Mientras falten, C02-C09 siguen abiertos.

## Alternativas descartadas

- **Contenedores (Docker/Kubernetes):** la plataforma no los usa, y agregarlos solo para UvoStore suma una capa que nadie opera.
- **Disco local para las imágenes:** obliga a respaldar otra ruta y se pierde con el VPS.
- **Un PostgreSQL propio:** duplica respaldos y monitorización sin aislar más que una base y roles separados con límite de conexiones.
