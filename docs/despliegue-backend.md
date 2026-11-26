# Instalar y desplegar un ambiente (IMP-05)

Cómo se arma staging (o production) en el servidor de la plataforma UvoPOS desde cero, cómo se despliega una versión, cómo se vuelve atrás y cómo se da de alta un dominio propio. Las decisiones están en `docs/adr/0001-infraestructura-plataforma-uvopos.md`; el frontend, en `docs/despliegue-frontend.md`.

Todo sale del release: el jar, el zip del frontend del ambiente, `uvostore-ops-<tag>.tar.gz` (los scripts y plantillas de `ops/` de ese mismo commit) y `SHA256SUMS`. Los scripts se ejecutan con `sudo bash` desde el directorio donde se descomprimió `ops/`.

## Qué hay en `ops/`

| Archivo | Qué hace |
|---|---|
| `servidor/provisionar.sh <ambiente>` | Usuario del sistema, carpetas, archivo de entorno con secretos nuevos, base y roles, unidad systemd y nginx. Se puede volver a correr. |
| `servidor/desplegar-backend.sh` | Verifica el jar con `SHA256SUMS`, cambia `current.jar` de forma atómica, reinicia y espera readiness. `--activar` vuelve a una versión anterior. |
| `nginx/publicar-frontend.sh` | Lo mismo para el frontend (`docs/despliegue-frontend.md`). |
| `servidor/dominio-propio.sh alta\|baja` | Certificado y server de nginx de un dominio propio. |
| `servidor/cloudflare-saas.sh activar\|desactivar` | Modo Cloudflare for SaaS: los dominios propios llegan por Cloudflare sin certificado por dominio (`nginx/cloudflare-saas.conf.template`, `nginx/cloudflare-ips.txt`). |
| `servidor/verificar.sh` | Comprueba el ambiente y deja un informe sin secretos: es la evidencia. |
| `servidor/uvostore.env.example` | Todas las variables del backend, con su motivo. |
| `servidor/uvostore.service.template` | La unidad systemd (límites, apagado ordenado, endurecimiento). |
| `postgres/roles.sql` | Base y roles del ambiente; también sirve para rotar sus contraseñas. |
| `nginx/uvostore.conf.template`, `nginx/dominio-propio.conf.template`, `nginx/default.conf` | El server del ambiente, el de cada dominio propio y el que corta los hosts desconocidos. |
| `s3/*.json` | La política IAM de la aplicación y la de lectura pública del bucket. Desde IMP-09, también las del bucket de respaldo y el ciclo de vida de las versiones. |
| `respaldo/` | IMP-09: respaldo del clúster (pgBackRest) y de las imágenes, restauración aislada y su verificación. Ver `docs/recuperacion.md`. |
| `servidor/ensayo/` | El ensayo limpio que corre CI, y el de migración y vuelta atrás (`migracion-rollback.sh`). |

Cada ambiente usa sus propios nombres: usuario y servicio `uvostore-<ambiente>`, `/opt/uvostore-<ambiente>`, `/srv/uvostore-<ambiente>`, `/etc/uvostore/<ambiente>.env`, base `uvostore_<ambiente>` y upstream `uvostore_<ambiente>`. Staging y production conviven en el mismo servidor sin compartir nada.

## Requisitos del servidor

- Ubuntu 24.04 (o 22.04 con nginx de nginx.org: hace falta **nginx 1.19.4 o posterior** por `ssl_reject_handshake`).
- `openjdk-21-jre-headless`, `nginx`, `postgresql` (el del POS), `certbot`, `curl`, `unzip`, `openssl`.
- Un único `default_server` en nginx. `provisionar.sh` se detiene si encuentra otro, como el `sites-enabled/default` de Ubuntu. Decidir cuál queda es de operaciones.
- `server_names_hash_bucket_size 64` en `nginx.conf` si los dominios son largos. nginx avisa con «could not build server_names_hash».

## Instalar un ambiente desde cero

1. **DNS.** El dominio de plataforma del ambiente (`tiendas-staging.uvostore.cl`, por ejemplo) y su comodín apuntan al servidor. Staging usa un dominio distinto del de production.
2. **Certificado comodín** (DNS-01, con el plugin del proveedor DNS):
   ```bash
   sudo certbot certonly --dns-<proveedor> -d tiendas-staging.uvostore.cl -d '*.tiendas-staging.uvostore.cl'
   ```
3. **S3:**
   - Crear un bucket por ambiente y aplicarle `ops/s3/politica-lectura-publica.json`. Reemplazar `@@BUCKET@@`, y dejar desactivado «Block public access» solo para las políticas del bucket.
   - Crear un usuario IAM para el ambiente con `ops/s3/politica-iam-aplicacion.json`. Sus claves van al archivo de entorno.
4. **Primera pasada:**
   ```bash
   sudo DOMINIO_TIENDAS=tiendas-staging.uvostore.cl bash ops/servidor/provisionar.sh staging
   ```
   - Crea `/etc/uvostore/staging.env` con JWT, clave de plataforma, clave de cifrado, token de métricas y contraseñas de la base recién generados.
   - Se detiene (código 2) y lista lo que falta completar a mano: bucket, claves IAM, SMTP y remitente.
5. **Completar el archivo de entorno.** Editar `/etc/uvostore/staging.env`:
   - Completar los valores `CAMBIAR_`.
   - Opcional: `SENTRY_DSN`, y `POS_ENABLED`/`WEBPAY_ENABLED` según C01.
   - **Guardar `APP_ENCRYPTION_KEY` en el gestor de secretos ahora.** Sin ella, las credenciales de pasarela guardadas no se pueden leer.
   - Entregar `PLATFORM_API_KEY` al equipo del POS.
6. **Segunda pasada:** el mismo comando. Deja:
   - la base y los roles (imprime la tabla de comprobación);
   - la unidad habilitada;
   - nginx recargado.
7. **Desplegar:**
   ```bash
   sudo bash ops/servidor/desplegar-backend.sh uvo-store-v1.4.0.jar staging SHA256SUMS
   sudo bash ops/nginx/publicar-frontend.sh frontend-dist-v1.4.0-staging.zip /srv/uvostore-staging/frontend SHA256SUMS
   ```
   El primer arranque aplica todas las migraciones con el rol migrador. Si una regla de configuración falla, el servicio no arranca y `journalctl -u uvostore-staging` dice cuál.
8. **Primera tienda** (desde el servidor, como UvoPOS):
   ```bash
   curl -X POST http://127.0.0.1:8081/api/platform/stores -H "X-Platform-Key: <PLATFORM_API_KEY>" \
     -H 'X-Platform-Operator: <quien>' -H 'Content-Type: application/json' \
     -d '{"slug":"demo","domain":"","storeName":"Demo","adminName":"...","adminEmail":"..."}'
   ```
9. **Evidencia:**
   ```bash
   sudo bash ops/servidor/verificar.sh staging --tienda demo --imagen <url de una imagen subida> --reinicio \
     | tee verificacion-staging-$(date +%F).txt
   ```
   Para C03, correrlo también con `--por-dns`: las URL públicas se piden por el DNS real (la IP pública del servidor, con sus certificados) en lugar de forzarse a `127.0.0.1`.

Antes de admitir tráfico, revisar además `docs/configuracion-ambientes.md`, «Antes de admitir tráfico».

## Desplegar una versión

```bash
sudo bash ops/servidor/desplegar-backend.sh uvo-store-<tag>.jar <ambiente> SHA256SUMS
sudo bash ops/nginx/publicar-frontend.sh frontend-dist-<tag>-<ambiente>.zip /srv/uvostore-<ambiente>/frontend SHA256SUMS
```

- Usar los scripts del `uvostore-ops-<tag>.tar.gz` del mismo tag.
- Si cambiaron plantillas o reglas, correr antes `provisionar.sh <ambiente>`. Es idempotente y no toca el archivo de entorno.
- El backend se reinicia: con una sola instancia hay unos segundos sin servicio. Primero el backend, después el frontend. Las pestañas abiertas siguen funcionando gracias a los chunks heredados.
- `desplegar-backend.sh` deja `SENTRY_RELEASE=<tag>` en el archivo de entorno, el mismo valor que lleva el frontend.

## Volver atrás

- **Backend:** `sudo bash ops/servidor/desplegar-backend.sh --activar <ambiente> uvo-store-<tag-anterior>`. Se conservan las últimas 3 versiones.
- **Frontend:** `sudo bash ops/nginx/publicar-frontend.sh --activar /srv/uvostore-<ambiente>/frontend frontend-dist-<tag-anterior>-<ambiente>`.
- **Si la versión nueva aplicó migraciones** (`select * from flyway_schema_history order by installed_rank desc`), la anterior puede no arrancar. Hibernate valida el esquema al iniciar, y las migraciones no se deshacen.
  - Qué migración lo permite y cuál no está en la tabla de `docs/recuperacion.md#migraciones` (V25 y V36 no lo permiten).
  - Si no se puede volver con el script, se corrige hacia adelante o se restaura y se concilia (`docs/recuperacion.md`).
  - Desde IMP-09, `desplegar-backend.sh` exige un respaldo de menos de una hora antes de activar un jar con migraciones pendientes (`SIN_RESPALDO=1` para saltarlo, a sabiendas). `--activar` avisa si el esquema es más nuevo que el jar al que se vuelve.
- `desplegar-backend.sh` nunca vuelve atrás solo. Si la versión nueva no queda lista, muestra el log y el comando, para que decida una persona.

## Dominio propio

El backend resuelve la tienda por `stores.domain` y nginx necesita un server con su certificado. Orden:

1. El cliente apunta su dominio (registro A/AAAA) a la IP del servidor.
2. `sudo bash ops/servidor/dominio-propio.sh alta <ambiente> www.mitienda.cl`
   - Obtiene el certificado por HTTP-01. `default.conf` responde el desafío aunque el dominio todavía no tenga server.
   - Renderiza el server, ejecuta `nginx -t` y recarga.
3. `PUT /api/platform/stores/<id>/domain {"domain":"www.mitienda.cl"}`.
4. Abrir `https://www.mitienda.cl/` y comprobar que muestra esa tienda.
5. `PUT /api/platform/stores/<id>/domain/verified {"verified":true}`. Desde aquí los correos y los retornos de pago usan el dominio propio; antes usaban `<slug>.<dominio de plataforma>`.

**Cambio de dominio:**
- Alta del nuevo (paso 2), PUT con el nuevo dominio y verificación otra vez: cambiar el dominio borra la verificación (PROD-04).
- Al hacer el PUT, el backend deja de resolver el viejo y responde 404 «Tienda no encontrada».
- Después, `dominio-propio.sh baja <ambiente> <viejo>`: nginx deja de servirlo y rechaza el handshake.

**Baja:** `DELETE /api/platform/stores/<id>/domain` quita el dominio de la tienda (y su verificación): el backend deja de resolverlo, los enlaces vuelven a `<slug>.<dominio de plataforma>` y el dominio queda libre para otra tienda. Después, `dominio-propio.sh baja` para que deje de llegar desde Internet. Cerrar la tienda también lo borra.

## Dominios propios con Cloudflare for SaaS

La alternativa a un certificado por dominio: el cliente apunta un CNAME a un nombre de la plataforma
(`customers.<zona>`), Cloudflare emite y renueva su certificado, y se conecta a este servidor con el `Host` del
cliente. No hay que dar de alta cada dominio en nginx; el panel de dominios de UVO hace el resto por la API de
plataforma (`PUT .../domain`, `PUT .../domain/verified`, `DELETE .../domain`).

En Cloudflare, en la zona de la plataforma:
1. Registro `A customers` → IP de este servidor, con proxy (nube naranja).
2. **SSL/TLS → Custom Hostnames**: habilitar Cloudflare for SaaS y poner `customers.<zona>` como *Fallback Origin*.
3. **SSL/TLS → Origin Server → Create Certificate** (Cloudflare Origin CA) para `customers.<zona>`. Guardar el
   certificado y la llave en el servidor (por ejemplo `/etc/ssl/uvostore/origen.pem` y `origen.key`, la llave con
   permisos 600).
4. Modo SSL/TLS **Full** y *Always Use HTTPS*. Con **Full (strict)**, comprobar antes que Cloudflare acepta el
   certificado de origen para los dominios de los clientes; si no, quedarse en Full.

En el servidor:
1. `sudo bash ops/servidor/cloudflare-saas.sh activar production /etc/ssl/uvostore/origen.pem /etc/ssl/uvostore/origen.key`
   - Reemplaza `default.conf` por `nginx/cloudflare-saas.conf.template`: el 443 por omisión atiende al ambiente,
     solo para conexiones desde las redes de Cloudflare (`nginx/cloudflare-ips.txt`); el resto recibe 444.
   - Toma la IP del comprador de `CF-Connecting-IP`, solo si viene de Cloudflare, y la pasa al backend como
     cualquier otra petición: no hace falta tocar `RATE_LIMIT_TRUSTED_PROXIES`.
   - El 80 queda igual que en `default.conf` (444, salvo el desafío de Certbot).
2. Firewall: el 443 basta con abrirlo a las redes de Cloudflare.
3. `sudo bash ops/servidor/cloudflare-saas.sh desactivar` vuelve a `default.conf`. `provisionar.sh` también lo
   reinstala: después de volver a provisionar, correr `activar` otra vez.

Los rangos de `cloudflare-ips.txt` hay que revisarlos contra <https://www.cloudflare.com/ips/> antes de activar y
cada cierto tiempo: una conexión desde un rango nuevo que no está en la lista recibe 444.

Los dominios con certificado propio (`dominio-propio.sh`) y el comodín de la plataforma siguen funcionando igual:
tienen su `server_name`, así que no caen en el server por omisión.

## Rotación de secretos

- **Qué se puede rotar y con qué efecto:** está en `docs/configuracion-ambientes.md`, «Secretos y rotación».
- **Contraseñas de la base:**
  1. Cambiar `DB_PASSWORD` o `FLYWAY_PASSWORD` en el archivo de entorno.
  2. Correr `provisionar.sh <ambiente>`, que aplica `roles.sql` con las contraseñas nuevas.
  3. `sudo systemctl restart uvostore-<ambiente>`.
- **Los demás secretos:** se editan en el archivo de entorno y se reinicia el servicio.
- **`APP_ENCRYPTION_KEY`:** no se rota.

## Correo (C11)

**Cómo salen los correos.** Todos los correos del backend salen por el outbox (`outbox_messages`), escritos en la misma transacción que los origina, y los entrega `OutboxDispatcher` cada 10 s con reintentos:
- los de pedidos (canal `mail`);
- desde IMP-06, los de acceso al panel (canal `access`): invitación de administrador y su reenvío, invitación del dueño al crear la tienda, recuperación de acceso del dueño y «¿Olvidaste tu contraseña?».

**Qué pasa cuando falla el SMTP.**
- Si cae o no responde, nada se pierde: la fila queda `PENDING` con el error y se reintenta con espera creciente (1, 2, 4… hasta 30 min) durante 48 h (`OUTBOX_GIVE_UP_AFTER_HOURS`). Pasado ese plazo queda `FAILED`.
- Una dirección rechazada por el servidor queda `FAILED` al momento.
- Un correo de acceso nunca lleva un enlace que ya no vale. La fila guarda la huella SHA-256 del token, no el token, y al entregarse se compara con el vigente: si se reenvió otro, se usó o venció, la fila queda `SKIPPED` con el motivo.
- Un resultado incierto (el proceso murió enviando) se reintenta; un duplicado lleva el mismo enlace de un solo uso.

**Dónde se ve un fallo.**
- Alerta `UvoStoreOutboxFallido{channel="access"}` y `UvoStoreCorreosDeAccesoDetenidos` (más de 10 min en cola). Runbook en `docs/observabilidad.md`.
- Página «Envíos fallidos» del panel de cada tienda (reintentar o marcar resuelto, con nota).
- `GET /api/platform/access-emails/failed[?storeId=]` y `POST /api/platform/access-emails/{id}/retry|resolve` con `{"note": "..."}`, para el dueño de una tienda nueva que no tiene a nadie que entre al panel.
- Reintentar solo sirve si el enlace sigue vigente. Si venció, la salida es «Reenviar invitación» (panel), `POST /api/platform/stores/{id}/owner/access-recovery` o pedir de nuevo el restablecimiento.

**Configurar el proveedor (pendiente, con el proveedor elegido):**
1. Un relay SMTP autenticado con TLS en `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH=true` y `MAIL_SMTP_STARTTLS=true`. Fuera de desarrollo el arranque exige `MAIL_HOST`.
2. `MAIL_FROM` con un dominio propio verificado en el proveedor, con SPF, DKIM y DMARC publicados en su DNS.
3. Prueba de entregabilidad real con la cuenta de staging:
   - una invitación y un «¿Olvidaste tu contraseña?» a buzones de Gmail y Outlook;
   - comprobar que llegan a la bandeja de entrada (no a spam) y que las cabeceras dicen `spf=pass`, `dkim=pass` y `dmarc=pass`;
   - archivar las cabeceras como evidencia.
4. Ensayar el fallo en staging: credenciales SMTP inválidas, pedir un restablecimiento y ver la alerta y la fila en cola. Corregirlas y ver que el correo sale solo.

## El ensayo de CI

El job **`ensayo-servidor`** de `ci.yml` (`ops/servidor/ensayo/ensayo.sh`) construye staging desde cero en una máquina Ubuntu 24.04 nueva, solo con estos scripts y los artefactos armados como en `release.yml`.

**Comprueba:**
- La primera pasada se detiene pidiendo los valores, la segunda deja todo listo y la tercera no cambia nada.
- El servicio arranca con `APP_ENVIRONMENT=staging`, con todas las reglas de arranque: el rol de la app sin DDL y Flyway con el rol migrador.
- Dos tiendas:
  - el token de una no sirve en la otra;
  - un `X-Forwarded-For` que no viene del proxy se rechaza.
- Una imagen en S3 (S3Mock) sobrevive a reinicio, redespliegue con otra versión y vuelta atrás.
- Dominio propio: alta, verificación y cambio. El viejo da 404 en el backend y, dado de baja, nginx rechaza su handshake.
- C11: con Mailpit detenido, «¿Olvidaste tu contraseña?» responde igual y la fila queda en cola con el error. Sobrevive a un reinicio del servicio y el correo sale solo al volver el SMTP. La fila no guarda el token, y un segundo pedido deja sin efecto el primer enlace.
- `verificar.sh` completo, con apagado ordenado. Su informe queda como artefacto `ensayo-servidor` (30 días).

**Simulado:** certificados autofirmados, S3Mock en lugar de S3 (no aplica políticas de bucket: las de `ops/s3/` solo se validan como JSON), Mailpit en lugar del SMTP, y nombres `.test` sin DNS.

Es la «ejecución limpia» del paso 7 de IMP-05. Lo que solo se ve en el servidor real queda en la tabla siguiente.

## Evidencia por punto del checklist

| Punto | Qué la produce | Qué falta en el servidor real |
|---|---|---|
| C02: staging desde instrucciones, artefactos identificados, proceso supervisado, apagado ordenado | Este documento, `ensayo-servidor`, `verificar.sh --reinicio` (jar y sha256, frontend, Flyway, «Graceful shutdown complete») | Instalarlo en el VPS y archivar el informe de `verificar.sh`. Repetirlo con otro operador |
| C03: dos tiendas HTTPS, dominio propio, renovación, hosts desconocidos, backend y DB privados | `verificar.sh` (loopback, 444/handshake, 404 del backend, certificados, timer y hook de Certbot), `dominio-propio.sh` | DNS y certificados reales. Correr `verificar.sh --por-dns` con dos tiendas y un dominio propio real |
| C04: Host preservado, proxies de confianza, spoofing rechazado, contadores separados | `ensayo-servidor` (token cruzado, spoofing), `TrustedProxyRateLimitTest`, `PlatformNetworkBehindProxyTest` | Repetir el spoofing desde fuera contra el dominio real |
| C05: caché por host, privado sin caché compartida, SPA recargable | E2E en modalidad `dist` (IMP-04) y `verificar.sh` (no-cache, no-store, recarga directa) | Ninguna CDN delante; si se agrega, revisarla |
| C07: ambiente correcto, secretos nuevos, roles separados, rotación | `provisionar.sh` (secretos generados en el servidor, roles con nombre por ambiente), `verificar.sh` (roles, 640, configuración enmascarada), el arranque (`DatabaseRole`) | Custodia de `APP_ENCRYPTION_KEY` en el gestor de contraseñas del equipo, con dos custodios nominales y su prueba trimestral (procedimiento en `docs/recuperacion.md`, «Custodia») |
| C11: invitación, reset y pedidos recibidos; remitente autenticado; fallo de SMTP visible y recuperable | `AccessEmailOutboxTest` (caída tras el commit, SMTP caído, huérfana, token reemplazado, usado o vencido, recuperación desde la plataforma), `PlatformAccessEmailControllerTest`, `ensayo-servidor` (SMTP caído con reinicio en medio), la alerta `UvoStoreCorreosDeAccesoDetenidos` (promtool) | El proveedor SMTP real: SPF/DKIM/DMARC, recepción en bandeja de entrada de invitación, reset y pedido, y el ensayo de fallo en staging (sección «Correo (C11)») |
| C12: cada pasarela habilitada supera la matriz de §10 con credenciales y callbacks reales | Webpay contra el ambiente de integración de Transbank, 10 casos el 18-nov (`frontend/e2e/homologacion/webpay.spec.ts`); interruptores `WEBPAY_ENABLED`, `STRIPE_ENABLED` y `MERCADOPAGO_ENABLED` (`PaymentPlatformSwitchTest`); transferencia bancaria (`BankTransferTest`); regresiones `WebpayUncertainCommitTest` y `pasarelas.spec.ts` 5 y 5b. El expediente está en `docs/homologacion-pagos.md` | MercadoPago con credenciales de prueba; staging HTTPS con subdominio y dominio propio; compra controlada en producción; custodios de credenciales |
| C09: imágenes persistentes, permisos, URL https | `ensayo-servidor` (reinicio, redespliegue, vuelta atrás), `verificar.sh --imagen` | El bucket real con su política, y subir una imagen y repetirlo tras un redespliegue |
| C10: respaldo de DB y archivos, custodia de la llave, restauración aislada con RPO/RTO medidos | `ensayo-servidor` (paso C10) y `docs/recuperacion.md` | Lo que lista `docs/recuperacion.md`, «Evidencia por punto del checklist» |
| C19: migración y vuelta atrás ensayadas, incompatibilidades documentadas | `migracion-rollback` (CI), la tabla de `docs/recuperacion.md#migraciones`, las comprobaciones de `desplegar-backend.sh` | Lo mismo, sobre una copia representativa de staging |

La ausencia de accesos (VPS, DNS, AWS, SMTP) es una dependencia pendiente, no un punto cerrado. Los Cxx los aprueba el responsable con estas evidencias (`GO_LIVE_CHECKLIST.md` §12).
