#!/usr/bin/env bash
# IMP-05 (paso 7). Ensayo limpio: construye staging desde cero con los scripts de ops/ en una máquina Ubuntu nueva
# y comprueba lo que esos scripts prometen. IMP-06 le suma C11: un correo de acceso que sobrevive a una caída del SMTP
# y a un reinicio. Lo corre el job «ensayo-servidor» de .github/workflows/ci.yml; se puede
# repetir en una VM Ubuntu 24.04 desechable. NUNCA en el servidor de la plataforma: borra el sitio por omisión de
# nginx y crea tiendas de prueba.
#
#   sudo bash ops/servidor/ensayo/ensayo.sh <uvo-store-X.jar> <frontend-dist-X-staging.zip> <SHA256SUMS> <carpeta-informe>
#
# Requisitos (los instala el job): PostgreSQL corriendo, nginx, Java 21, curl, jq, aws (CLI), S3Mock (adobe/s3mock)
# en 127.0.0.1:9090 y Mailpit en 127.0.0.1:1025 (SMTP) y :8025 (API).
#
# Qué es simulado y qué no:
#   real      systemd, la unidad y sus límites; PostgreSQL con los roles de roles.sql y APP_ENVIRONMENT=staging, así
#             que corren TODAS las reglas de arranque, DatabaseRole incluido; nginx con las mismas plantillas;
#             Flyway con el rol migrador; el despliegue, la vuelta atrás y la publicación del frontend.
#   simulado  certificados autofirmados en lugar de Let's Encrypt; S3Mock en lugar de S3 (misma API para subir, leer
#             y borrar; no aplica políticas de bucket, así que la de lectura pública solo se valida); Mailpit en lugar
#             del SMTP real; nombres .test resueltos a 127.0.0.1 con curl --resolve en lugar de DNS.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVIDOR="$(cd "$DIR/.." && pwd)"
OPS="$(cd "$SERVIDOR/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/..
. "$SERVIDOR/comun.sh"

[ $# -eq 4 ] || die "uso: $0 <jar> <zip-frontend> <SHA256SUMS> <carpeta-informe>"
JAR="$(readlink -f "$1")"
ZIP="$(readlink -f "$2")"
SUMAS="$(readlink -f "$3")"
INFORME="$4"
requerir_root
requerir_comandos jq aws curl

AMBIENTE=staging
DOMINIO=tiendas.test
PROPIO=tienda-propia.test
PROPIO_NUEVO=otra-propia.test
IMAGENES=imagenes.test
BUCKET=uvostore-staging-ensayo
ENV_FILE="$(archivo_env "$AMBIENTE")"
CLAVE_ADMIN="ensayo-$(openssl rand -hex 8)"
export CERTIFICADOS=autofirmados

mkdir -p "$INFORME"
paso() { echo; echo "######## $*"; }
esperar() { # esperar <descripción> <segundos> <comando...>
    local desc="$1" limite="$2" i=0
    shift 2
    until "$@" > /dev/null 2>&1; do
        i=$((i + 1))
        [ "$i" -le "$limite" ] || die "no se cumplió: $desc"
        sleep 1
    done
}
https() { # https <host> <curl args...> : contra este nginx, sin validar el autofirmado
    local host="$1"
    shift
    curl -sk --max-time 20 --resolve "$host:443:127.0.0.1" "$@"
}
codigo_de() { https "$1" -o /dev/null -w '%{http_code}' "${@:2}"; }
afirmar_igual() { # afirmar_igual <descripción> <esperado> <obtenido>
    [ "$2" = "$3" ] || die "$1: se esperaba '$2' y se obtuvo '$3'"
    echo "OK  $1"
}
# Para lo que depende de una recarga de nginx: "systemctl reload nginx" vuelve en cuanto manda la señal, y durante
# un instante los workers viejos (con la configuración anterior) todavía aceptan conexiones nuevas. Reintenta hasta
# 15 s antes de dar por fallida la comprobación.
pronto() { # pronto <descripción> <esperado> <comando...>
    local desc="$1" esperado="$2" obtenido=""
    shift 2
    for _ in $(seq 1 15); do
        # Sin el || true, un curl que falla durante la recarga cortaría el script (set -e) en lugar de reintentar.
        obtenido="$("$@")" || true
        [ "$obtenido" = "$esperado" ] && break
        sleep 1
    done
    afirmar_igual "$desc" "$esperado" "$obtenido"
}
# El código de salida de curl al abrir https://<host>/ (35 = handshake rechazado).
salida_tls() { https "$1" -o /dev/null "https://$1/" && echo 0 || echo $?; }

# ---- Máquina limpia ------------------------------------------------------------------------------------------
paso "preparación"
# verificar.sh pide la imagen por su URL tal cual, sin --resolve.
grep -q " $IMAGENES\$" /etc/hosts || echo "127.0.0.1 $IMAGENES" >> /etc/hosts
# La decisión que provisionar.sh deja a operaciones: el sitio de ejemplo de Ubuntu tiene default_server.
rm -f /etc/nginx/sites-enabled/default
systemctl reload nginx || systemctl start nginx

# S3Mock no valida credenciales; van igual porque el SDK y la CLI firman todas las peticiones.
export AWS_ACCESS_KEY_ID=ensayo AWS_SECRET_ACCESS_KEY=ensayo AWS_DEFAULT_REGION=us-east-1
aws configure set default.s3.addressing_style path
S3=(aws --endpoint-url http://127.0.0.1:9090 s3api)
"${S3[@]}" create-bucket --bucket "$BUCKET" > /dev/null
# S3Mock no implementa PutBucketPolicy (y sirve los objetos sin autenticar, como el bucket público). La política
# solo se puede ejercitar en S3 de verdad; aquí se comprueba que se renderiza y es JSON con el bucket correcto.
for politica in politica-lectura-publica politica-iam-aplicacion; do
    tmp="$(mktemp)"
    renderizar "$OPS/s3/$politica.json" "$tmp" BUCKET="$BUCKET"
    jq -e --arg r "arn:aws:s3:::$BUCKET/*" '.Statement[0].Resource == $r' "$tmp" > /dev/null \
        || die "ops/s3/$politica.json no queda como JSON válido para $BUCKET"
done

# ---- Provisión -----------------------------------------------------------------------------------------------
paso "provisionar.sh, primera vez: crea el archivo de entorno y se detiene"
set +e
DOMINIO_TIENDAS="$DOMINIO" bash "$SERVIDOR/provisionar.sh" "$AMBIENTE"
estado=$?
set -e
afirmar_igual "la primera vez se detiene pidiendo los valores CAMBIAR_" 2 "$estado"
afirmar_igual "el archivo de entorno queda 640 root:uvostore-staging" "640 root:uvostore-staging" "$(stat -c '%a %U:%G' "$ENV_FILE")"
grep -q '@@' "$ENV_FILE" && die "quedaron marcadores en $ENV_FILE"
echo "OK  sin marcadores; secretos generados: $(grep -cE '^(JWT_SECRET|PLATFORM_API_KEY|APP_ENCRYPTION_KEY|METRICS_TOKEN|DB_PASSWORD|FLYWAY_PASSWORD)=.{32,}' "$ENV_FILE") de 6"

# Lo que completaría una persona: aquí, los servicios del ensayo.
fijar_env "$ENV_FILE" APP_STORAGE_S3_BUCKET "$BUCKET"
fijar_env "$ENV_FILE" APP_STORAGE_S3_REGION us-east-1
fijar_env "$ENV_FILE" APP_STORAGE_S3_ACCESS_KEY ensayo
fijar_env "$ENV_FILE" APP_STORAGE_S3_SECRET_KEY ensayo
fijar_env "$ENV_FILE" APP_STORAGE_S3_ENDPOINT http://127.0.0.1:9090
fijar_env "$ENV_FILE" APP_STORAGE_S3_PUBLIC_BASE_URL "https://$IMAGENES/$BUCKET"
fijar_env "$ENV_FILE" MAIL_HOST 127.0.0.1
fijar_env "$ENV_FILE" MAIL_PORT 1025
fijar_env "$ENV_FILE" MAIL_USERNAME ""
fijar_env "$ENV_FILE" MAIL_PASSWORD ""
fijar_env "$ENV_FILE" MAIL_SMTP_AUTH false
fijar_env "$ENV_FILE" MAIL_SMTP_STARTTLS false
fijar_env "$ENV_FILE" MAIL_FROM "ensayo@$DOMINIO"

paso "provisionar.sh, segunda vez: deja el ambiente listo"
bash "$SERVIDOR/provisionar.sh" "$AMBIENTE"
paso "provisionar.sh, tercera vez: idempotente"
antes="$(sha256sum "$ENV_FILE" /etc/systemd/system/uvostore-staging.service /etc/nginx/conf.d/uvostore-staging.conf)"
bash "$SERVIDOR/provisionar.sh" "$AMBIENTE"
afirmar_igual "volver a correrlo no cambia nada" "$antes" \
    "$(sha256sum "$ENV_FILE" /etc/systemd/system/uvostore-staging.service /etc/nginx/conf.d/uvostore-staging.conf)"

renderizar "$DIR/imagenes.conf.template" /etc/nginx/conf.d/ensayo-imagenes.conf \
    HOST="$IMAGENES" CERTS="$UVOSTORE_ETC/certs-autofirmados/$DOMINIO"
# El autofirmado del comodín no cubre imagenes.test, pero el ensayo no valida certificados.
nginx -t && systemctl reload nginx

# ---- Despliegue ----------------------------------------------------------------------------------------------
paso "desplegar-backend.sh"
bash "$SERVIDOR/desplegar-backend.sh" "$JAR" "$AMBIENTE" "$SUMAS"
VERSION_1="$(basename "$JAR" .jar)"
PUERTO="$(valor_env "$ENV_FILE" SERVER_PORT)"
afirmar_igual "SENTRY_RELEASE es el tag desplegado" "${VERSION_1#uvo-store-}" "$(valor_env "$ENV_FILE" SENTRY_RELEASE)"
journalctl -u uvostore-staging --no-pager | grep -q 'APP_ENVIRONMENT=staging: configuración de producción verificada' \
    || die "no aparece la verificación de configuración de staging en el log"
echo "OK  arrancó con APP_ENVIRONMENT=staging y todas sus reglas (DatabaseRole incluido)"

paso "publicar-frontend.sh"
bash "$OPS/nginx/publicar-frontend.sh" "$ZIP" "${UVOSTORE_SRV_PREFIX}$AMBIENTE/frontend" "$SUMAS"

# ---- Tiendas -------------------------------------------------------------------------------------------------
PLATFORM_KEY="$(valor_env "$ENV_FILE" PLATFORM_API_KEY)"
plataforma() { # plataforma <método> <ruta> [json] : directo al backend desde 127.0.0.1, como UvoPOS
    curl -sS --max-time 20 -X "$1" "http://127.0.0.1:$PUERTO$2" -H "X-Platform-Key: $PLATFORM_KEY" \
        -H 'X-Platform-Operator: ensayo' -H 'Content-Type: application/json' ${3:+-d "$3"}
}
crear_tienda() { # crear_tienda <slug> -> storeId
    plataforma POST /api/platform/stores \
        "{\"slug\":\"$1\",\"domain\":\"\",\"storeName\":\"Tienda $1\",\"adminName\":\"Ensayo\",\"adminEmail\":\"$1@ensayo.test\"}" \
        | jq -er .storeId
}
token_de_invitacion() { # token_de_invitacion <correo>
    local id
    id="$(curl -s "http://127.0.0.1:8025/api/v1/search?query=to:$1" | jq -r '.messages[0].ID // empty')"
    [ -n "$id" ] || return 1
    curl -s "http://127.0.0.1:8025/api/v1/message/$id" | jq -r .Text | grep -o 'token=[A-Za-z0-9_-]*' | head -n 1 | cut -d= -f2
}
# El token del correo de restablecimiento más reciente para <correo> que no sea <token-anterior> (vacío si no hay).
token_de_reset() { # token_de_reset <correo> [token-anterior]
    local id token
    for id in $(curl -s "http://127.0.0.1:8025/api/v1/search?query=to:$1" | jq -r '.messages[].ID'); do
        token="$(curl -s "http://127.0.0.1:8025/api/v1/message/$id" | jq -r .Text \
            | grep -o 'reset-password?token=[A-Za-z0-9_-]*' | head -n 1 | cut -d= -f2)"
        if [ -n "$token" ] && [ "$token" != "${2:-}" ]; then
            echo "$token"
            return 0
        fi
    done
    return 1
}
entrar() { # entrar <slug> -> JWT del panel, aceptando la invitación del dueño
    local correo="$1@ensayo.test" token
    esperar "invitación para $correo" 60 token_de_invitacion "$correo"
    token="$(token_de_invitacion "$correo")"
    https "$1.$DOMINIO" -X POST "https://$1.$DOMINIO/api/admin/auth/accept-invitation" \
        -H 'Content-Type: application/json' -d "{\"token\":\"$token\",\"password\":\"$CLAVE_ADMIN\"}" | jq -er .token
}

paso "dos tiendas por la API de plataforma"
TIENDA_A="$(crear_tienda ensayo-a)"
TIENDA_B="$(crear_tienda ensayo-b)"
JWT_A="$(entrar ensayo-a)"
JWT_B="$(entrar ensayo-b)"
echo "OK  tiendas $TIENDA_A y $TIENDA_B, con sus dueños dentro del panel"

# La API de plataforma responde 404, como si no existiera, a quien no está en su red (PlatformApiKeyAuthFilter).
afirmar_igual "C04: un X-Forwarded-For de quien no es el proxy se rechaza en /api/platform" 404 \
    "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PUERTO/api/platform/stores/$TIENDA_A" \
        -H "X-Platform-Key: $PLATFORM_KEY" -H 'X-Forwarded-For: 127.0.0.1')"
afirmar_igual "C04: el token de la tienda A no sirve en la tienda B" 401 \
    "$(codigo_de ensayo-b."$DOMINIO" -H "Authorization: Bearer $JWT_A" "https://ensayo-b.$DOMINIO/api/admin/products")"
afirmar_igual "cada token sirve en su tienda" 200 \
    "$(codigo_de ensayo-b."$DOMINIO" -H "Authorization: Bearer $JWT_B" "https://ensayo-b.$DOMINIO/api/admin/products")"

# ---- Imágenes persistentes (C09) -----------------------------------------------------------------------------
paso "imagen subida a S3 (S3Mock)"
png="$(mktemp --suffix=.png)"
base64 -d > "$png" <<< 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
IMAGEN="$(https "ensayo-a.$DOMINIO" -X POST "https://ensayo-a.$DOMINIO/api/admin/home/banners" \
    -H "Authorization: Bearer $JWT_A" -F title=Ensayo -F active=true -F sortOrder=1 \
    -F ctaText="Ver tienda" -F ctaLink=/shop -F ctaNewTab=false \
    -F textPosition=left -F textColor=light -F overlayColor=#000000 -F overlayOpacity=0 \
    -F "newImage=@$png;type=image/png" | jq -er .image)"
echo "    $IMAGEN"
case "$IMAGEN" in "https://$IMAGENES/$BUCKET/"*) ;; *) die "la URL de la imagen no es la pública del bucket: $IMAGEN" ;; esac
imagen_sirve() { [ "$(codigo_de "$IMAGENES" "$IMAGEN")" = 200 ]; }
imagen_sirve || die "la imagen no se sirve"
"${S3[@]}" head-object --bucket "$BUCKET" --key "${IMAGEN#"https://$IMAGENES/$BUCKET/"}" > /dev/null \
    || die "la imagen no está en el bucket"
echo "OK  la imagen está en el bucket y se sirve por https"

paso "reinicio: la imagen sigue"
systemctl restart uvostore-staging
esperar_readiness "$PUERTO" 180 uvostore-staging || die "no volvió después del reinicio"
imagen_sirve || die "la imagen no sobrevivió al reinicio"
afirmar_igual "el banner sigue en la tienda" "$IMAGEN" \
    "$(https "ensayo-a.$DOMINIO" "https://ensayo-a.$DOMINIO/api/v1/home-banners" | jq -r '.[0].image')"

paso "redespliegue con otra versión y vuelta atrás: la imagen sigue"
VERSION_2="uvo-store-ensayo-2"
cp "$JAR" "$(dirname "$JAR")/$VERSION_2.jar"
sumas2="$(mktemp)"
(cd "$(dirname "$JAR")" && sha256sum "$VERSION_2.jar") > "$sumas2"
bash "$SERVIDOR/desplegar-backend.sh" "$(dirname "$JAR")/$VERSION_2.jar" "$AMBIENTE" "$sumas2"
afirmar_igual "current.jar apunta a la versión nueva" "$VERSION_2.jar" "$(basename "$(readlink "${UVOSTORE_OPT_PREFIX}$AMBIENTE/current.jar")")"
imagen_sirve || die "la imagen no sobrevivió al redespliegue"
bash "$SERVIDOR/desplegar-backend.sh" --activar "$AMBIENTE" "$VERSION_1"
afirmar_igual "--activar vuelve a la versión anterior" "$VERSION_1.jar" "$(basename "$(readlink "${UVOSTORE_OPT_PREFIX}$AMBIENTE/current.jar")")"
imagen_sirve || die "la imagen no sobrevivió a la vuelta atrás"
echo "OK  imagen y tienda intactas tras reinicio, redespliegue y vuelta atrás"

# ---- Dominio propio (C03) ------------------------------------------------------------------------------------
# Qué tienda responde en un host: solo la A tiene el banner con esa imagen.
banner_de() { https "$1" "https://$1/api/v1/home-banners" | jq -r '.[0].image // "sin banner"'; }

paso "dominio propio: alta, verificación y cambio"
bash "$SERVIDOR/dominio-propio.sh" alta "$AMBIENTE" "$PROPIO"
plataforma PUT "/api/platform/stores/$TIENDA_A/domain" "{\"domain\":\"$PROPIO\"}" > /dev/null
plataforma PUT "/api/platform/stores/$TIENDA_A/domain/verified" '{"verified":true}' | jq -e '.domainVerifiedAt != null' > /dev/null
pronto "el dominio propio sirve la tienda A" 200 codigo_de "$PROPIO" "https://$PROPIO/api/v1/store-settings"
afirmar_igual "y es la tienda A, no otra" "$IMAGEN" "$(banner_de "$PROPIO")"
afirmar_igual "la tienda B sigue en su subdominio, sin el banner de A" "sin banner" "$(banner_de "ensayo-b.$DOMINIO")"

bash "$SERVIDOR/dominio-propio.sh" alta "$AMBIENTE" "$PROPIO_NUEVO"
plataforma PUT "/api/platform/stores/$TIENDA_A/domain" "{\"domain\":\"$PROPIO_NUEVO\"}" \
    | jq -e '.domainVerifiedAt == null' > /dev/null || die "cambiar el dominio tiene que borrar la verificación"
echo "OK  cambiar el dominio deja la verificación pendiente"
afirmar_igual "el dominio viejo ya no resuelve la tienda en el backend" 404 \
    "$(codigo_de "$PROPIO" "https://$PROPIO/api/v1/store-settings")"
bash "$SERVIDOR/dominio-propio.sh" baja "$AMBIENTE" "$PROPIO"
pronto "dado de baja, nginx rechaza el handshake del dominio viejo" 35 salida_tls "$PROPIO"
plataforma PUT "/api/platform/stores/$TIENDA_A/domain/verified" '{"verified":true}' > /dev/null
pronto "el dominio nuevo sirve la tienda A" 200 codigo_de "$PROPIO_NUEVO" "https://$PROPIO_NUEVO/api/v1/store-settings"

# ---- Correos de acceso recuperables (C11, IMP-06) ------------------------------------------------------------
# Con el SMTP caído y un reinicio en medio, el enlace de «Olvidé mi contraseña» sale solo cuando el SMTP vuelve, y
# un segundo pedido deja sin efecto el primero. Es el outbox desplegado de verdad, con la unidad y PostgreSQL reales.
paso "C11: correo de acceso con el SMTP caído, un reinicio en medio y recuperación"
CORREO_A="ensayo-a@ensayo.test"
sql() { (cd / && runuser -u postgres -- psql -X -At -d "uvostore_$AMBIENTE" -c "$1"); }
olvide() { # olvide <correo> -> código HTTP de «¿Olvidaste tu contraseña?» en la tienda A
    codigo_de "ensayo-a.$DOMINIO" -X POST "https://ensayo-a.$DOMINIO/api/admin/auth/forgot-password" \
        -H 'Content-Type: application/json' -d "{\"email\":\"$1\"}"
}
restablecer() { # restablecer <token> -> código HTTP
    codigo_de "ensayo-a.$DOMINIO" -X POST "https://ensayo-a.$DOMINIO/api/admin/auth/reset-password" \
        -H 'Content-Type: application/json' -d "{\"token\":\"$1\",\"password\":\"$CLAVE_ADMIN-c11\"}"
}
fila_reset() { # estado|con-error o sin-error de la fila de reset más nueva de la tienda A
    # Texto explícito y no el booleano: concatenado, PostgreSQL lo convierte en 'true'/'false', no en el 't'/'f'
    # que psql muestra para una columna booleana suelta.
    sql "SELECT m.status || '|' || CASE WHEN m.last_error IS NULL THEN 'sin-error' ELSE 'con-error' END
         FROM outbox_messages m
         JOIN users u ON u.id = m.user_id
         WHERE u.email = '$CORREO_A' AND m.type = 'PASSWORD_RESET_EMAIL' ORDER BY m.id DESC LIMIT 1"
}
# Como esperar(), pero al rendirse dice en qué estado quedó la fila (vacío = no hay fila).
esperar_fila() { # esperar_fila <descripción> <segundos> <estado esperado>
    local i=0 visto=""
    until visto="$(fila_reset 2>&1)" && [ "$visto" = "$3" ]; do
        i=$((i + 1))
        [ "$i" -le "$2" ] || die "no se cumplió: $1 (se esperaba '$3', la fila está en '$visto')"
        sleep 1
    done
}
mailpit_arriba() { curl -fs -o /dev/null http://127.0.0.1:8025/api/v1/info; }

docker stop mailpit > /dev/null
afirmar_igual "C11: con el SMTP caído, el pedido de restablecimiento responde igual" 200 "$(olvide "$CORREO_A")"
esperar_fila "el despachador intenta, falla y deja la fila en cola" 60 "PENDING|con-error"
echo "OK  C11: la fila queda en cola con el error del intento"
sql "SELECT last_error FROM outbox_messages m JOIN users u ON u.id = m.user_id
     WHERE u.email = '$CORREO_A' AND m.type = 'PASSWORD_RESET_EMAIL' ORDER BY m.id DESC LIMIT 1" \
    | sed 's/^/    last_error: /'

systemctl restart uvostore-staging
esperar_readiness "$PUERTO" 180 uvostore-staging || die "no volvió después del reinicio"
afirmar_igual "C11: tras el reinicio la fila sigue en cola" "PENDING|con-error" "$(fila_reset)"

docker start mailpit > /dev/null
esperar "Mailpit de vuelta" 60 mailpit_arriba
# El reintento llega con la espera del despachador (1 minuto tras el primer fallo).
esperar "el correo de restablecimiento después de volver el SMTP" 180 token_de_reset "$CORREO_A"
TOKEN_RESET_1="$(token_de_reset "$CORREO_A")"
esperar_fila "C11: la fila queda entregada" 15 "SENT|sin-error"
echo "OK  C11: la fila queda entregada"
afirmar_igual "C11: la fila no guarda el token" 0 \
    "$(sql "SELECT count(*) FROM outbox_messages WHERE dedup_key LIKE '%$TOKEN_RESET_1%' OR token_hash = '$TOKEN_RESET_1'")"

afirmar_igual "C11: un segundo pedido responde igual" 200 "$(olvide "$CORREO_A")"
esperar "el segundo correo de restablecimiento" 60 token_de_reset "$CORREO_A" "$TOKEN_RESET_1"
TOKEN_RESET_2="$(token_de_reset "$CORREO_A" "$TOKEN_RESET_1")"
afirmar_igual "C11: el primer enlace ya no vale" 401 "$(restablecer "$TOKEN_RESET_1")"
afirmar_igual "C11: el enlace nuevo sí" 200 "$(restablecer "$TOKEN_RESET_2")"
afirmar_igual "C11: y es de un solo uso" 401 "$(restablecer "$TOKEN_RESET_2")"

# ---- Verificación final, la misma que en el servidor real ----------------------------------------------------
paso "verificar.sh"
set +e
bash "$SERVIDOR/verificar.sh" "$AMBIENTE" --tienda ensayo-a --dominio-propio "$PROPIO_NUEVO" --imagen "$IMAGEN"     --reinicio --inseguro | tee "$INFORME/verificacion-staging.txt"
estado=${PIPESTATUS[0]}
set -e
# Si algo falló, que el error lo diga sin tener que buscarlo en el log ni bajar el artefacto.
[ "$estado" = 0 ] || die "verificar.sh tuvo fallas:
$(grep '^FALLA' "$INFORME/verificacion-staging.txt")"
echo "OK  verificar.sh sin fallas"

# ---- Respaldo y restauración aislada (C10, IMP-09) ---------------------------------------------------------------
# Después de verificar.sh, a propósito: la restauración levanta otro clúster (5433) y otra instancia (8091), y nada de
# eso tiene que confundir a la verificación del ambiente.
#
# El escenario: un restablecimiento de contraseña queda en cola (SMTP caído) en el punto de restauración T, y después
# de T se entrega y se sube otro banner. La copia restaurada a T tiene que traer la fila en cola y no el banner, NO
# reenviar el correo (modo recuperación), descifrar las credenciales con la llave del gestor de contraseñas, y retener
# la fila para revisión. Con la llave equivocada, la sonda lo dice. El reinicio con trabajo en vuelo (§11.1) es el de C11.
paso "C10: políticas del respaldo"
BUCKET_RESPALDO=uvostore-respaldo-ensayo
BUCKET_AISLADO=uvostore-staging-restaurado
"${S3[@]}" create-bucket --bucket "$BUCKET_RESPALDO" > /dev/null
"${S3[@]}" create-bucket --bucket "$BUCKET_AISLADO" > /dev/null
for politica in politica-iam-respaldo politica-bucket-respaldo; do
    tmp="$(mktemp)"
    renderizar "$OPS/s3/$politica.json" "$tmp" BUCKET_RESPALDO="$BUCKET_RESPALDO" BUCKET_IMAGENES_STAGING="$BUCKET" \
        BUCKET_IMAGENES_PRODUCTION=uvostore-production-ensayo ARN_OPERACIONES=arn:aws:iam::000000000000:role/operaciones
    jq -e '.Statement | length > 0' "$tmp" > /dev/null || die "ops/s3/$politica.json no queda como JSON válido"
done
jq -e '.Rules | length > 0' "$OPS/s3/ciclo-vida-versiones.json" > /dev/null || die "ops/s3/ciclo-vida-versiones.json inválido"
echo "OK  C10: las políticas del bucket de respaldo se renderizan (S3Mock no las aplica)"

paso "C10: instalar-respaldo.sh"
ENV_RESPALDO="$UVOSTORE_ETC/respaldo.env"
set +e
bash "$OPS/respaldo/instalar-respaldo.sh"
estado=$?
set -e
afirmar_igual "C10: la primera vez se detiene pidiendo los valores CAMBIAR_" 2 "$estado"
afirmar_igual "C10: respaldo.env queda 600 root:root" "600 root:root" "$(stat -c '%a %U:%G' "$ENV_RESPALDO")"
afirmar_igual "C10: generó la clave de cifrado del repositorio" 1 \
    "$(grep -cE '^RESPALDO_CIPHER_PASS=.{32,}$' "$ENV_RESPALDO")"
# S3Mock sirve https en 9191 con un certificado propio; pgBackRest exige TLS.
fijar_env "$ENV_RESPALDO" RESPALDO_S3_BUCKET "$BUCKET_RESPALDO"
fijar_env "$ENV_RESPALDO" RESPALDO_S3_REGION us-east-1
fijar_env "$ENV_RESPALDO" RESPALDO_S3_ENDPOINT 127.0.0.1
fijar_env "$ENV_RESPALDO" RESPALDO_S3_KEY ensayo
fijar_env "$ENV_RESPALDO" RESPALDO_S3_KEY_SECRET ensayo
fijar_env "$ENV_RESPALDO" RESPALDO_S3_URI_STYLE path
fijar_env "$ENV_RESPALDO" RESPALDO_S3_PORT 9191
fijar_env "$ENV_RESPALDO" RESPALDO_S3_VERIFY_TLS n
fijar_env "$ENV_RESPALDO" RESPALDO_AWS_ENDPOINT_URL http://127.0.0.1:9090
fijar_env "$ENV_RESPALDO" RESPALDO_AMBIENTES staging
REPO_RESPALDO=s3
if ! bash "$OPS/respaldo/instalar-respaldo.sh" --reiniciar-postgresql > "$INFORME/instalar-respaldo-s3.log" 2>&1; then
    tail -n 30 "$INFORME/instalar-respaldo-s3.log"
    aviso "C10: pgBackRest no funcionó contra S3Mock (instalar-respaldo-s3.log); se ensaya con un repositorio posix."
    aviso "     Solo en el ensayo: en el servidor el repositorio es S3, y eso se comprueba en el primer respaldo real."
    fijar_env "$ENV_RESPALDO" RESPALDO_REPO_TIPO posix
    REPO_RESPALDO=posix
    bash "$OPS/respaldo/instalar-respaldo.sh" --reiniciar-postgresql
fi
echo "repositorio del ensayo: $REPO_RESPALDO" > "$INFORME/c10.txt"
# instalar-respaldo.sh reinició PostgreSQL (archive_mode): la instancia de staging se reconecta sola.
esperar_readiness "$PUERTO" 180 uvostore-staging || die "staging no volvió después de reiniciar PostgreSQL"
antes="$(sha256sum /etc/pgbackrest/pgbackrest.conf /etc/systemd/system/uvostore-respaldo-*.service /etc/systemd/system/uvostore-respaldo-*.timer)"
bash "$OPS/respaldo/instalar-respaldo.sh"
afirmar_igual "C10: volver a correrlo no cambia nada" "$antes" \
    "$(sha256sum /etc/pgbackrest/pgbackrest.conf /etc/systemd/system/uvostore-respaldo-*.service /etc/systemd/system/uvostore-respaldo-*.timer)"
afirmar_igual "C10: archive_mode activo" on "$(sql 'SHOW archive_mode')"
for t in completo diferencial imagenes metricas; do
    systemctl is-enabled --quiet "uvostore-respaldo-$t.timer" || die "el timer uvostore-respaldo-$t no quedó activo"
done
echo "OK  C10: timers de respaldo completo, diferencial, imágenes y métricas activos"

paso "C10: un secreto cifrado en la base, para la sonda de descifrado"
LLAVE_APP="$(valor_env "$ENV_FILE" APP_ENCRYPTION_KEY)"
CIFRADO="$(echo 'sk_test_ensayo_c10' | LLAVE="$LLAVE_APP" java "$DIR/CifrarSecreto.java")"
sql "INSERT INTO settings (store_id, key, value, created_at, updated_at)
     VALUES ($TIENDA_A, 'stripe_secret_key', '$CIFRADO', now(), now())
     ON CONFLICT (store_id, key) DO UPDATE SET value = EXCLUDED.value" > /dev/null
echo "OK  C10: stripe_secret_key de la tienda A, cifrada con la llave de staging"

paso "C10: respaldo de imágenes y respaldo completo"
/usr/local/sbin/uvostore-respaldar-imagenes
CLAVE_IMAGEN="${IMAGEN#"https://$IMAGENES/$BUCKET/"}"
"${S3[@]}" head-object --bucket "$BUCKET_RESPALDO" --key "imagenes/$AMBIENTE/$CLAVE_IMAGEN" > /dev/null \
    || die "la imagen no llegó al bucket de respaldo"
echo "OK  C10: la imagen está en s3://$BUCKET_RESPALDO/imagenes/$AMBIENTE/"
systemctl start uvostore-respaldo-completo
(cd / && runuser -u postgres -- pgbackrest --stanza=uvopos info) | tee "$INFORME/pgbackrest-info.txt"
grep -q '^uvostore_backup_last_success_timestamp_seconds{type="full"} [0-9]' /var/lib/prometheus/node-exporter/uvostore_respaldo.prom \
    || die "las métricas no registran el respaldo completo"
grep -q '^uvostore_images_backup_last_success_timestamp_seconds{environment="staging"} [0-9]' \
    /var/lib/prometheus/node-exporter/uvostore_respaldo_imagenes.prom || die "las métricas no registran la copia de imágenes"
echo "OK  C10: respaldo completo terminado y en las métricas"

paso "C10: el punto de restauración, con un correo en cola"
correos_de() { curl -s "http://127.0.0.1:8025/api/v1/search?query=to:$1" | jq '.messages | length'; }
docker stop mailpit > /dev/null
afirmar_igual "C10: pedido de restablecimiento con el SMTP caído" 200 "$(olvide "$CORREO_A")"
esperar_fila "C10: la fila queda en cola" 60 "PENDING|con-error"
FILA_C10="$(sql "SELECT m.id FROM outbox_messages m JOIN users u ON u.id = m.user_id
                 WHERE u.email = '$CORREO_A' AND m.type = 'PASSWORD_RESET_EMAIL' ORDER BY m.id DESC LIMIT 1")"
bash "$OPS/respaldo/verificar-restauracion.sh" "$AMBIENTE" --conteos > "$INFORME/conteos-origen.txt"
sleep 2
PUNTO="$(date -u '+%Y-%m-%d %H:%M:%S+00')"
PUNTO_ISO="$(date -u -d "$PUNTO" +%Y-%m-%dT%H:%M:%SZ)"
sleep 2
echo "    punto de restauración: $PUNTO"

paso "C10: después del punto, el correo sale y se sube otro banner"
docker start mailpit > /dev/null
esperar "Mailpit de vuelta" 60 mailpit_arriba
esperar_fila "C10: la fila se entrega después del punto" 180 "SENT|sin-error"
# El restablecimiento de C11 subió tokenVersion: JWT_A ya no vale. Se entra con la clave que dejó C11.
JWT_A="$(https "ensayo-a.$DOMINIO" -X POST "https://ensayo-a.$DOMINIO/api/admin/auth/login" \
    -H 'Content-Type: application/json' -d "{\"email\":\"$CORREO_A\",\"password\":\"$CLAVE_ADMIN-c11\"}" \
    | jq -er .token)" || die "C10: el dueño de la tienda A no pudo entrar con la clave restablecida en C11"
respuesta="$(https "ensayo-a.$DOMINIO" -X POST "https://ensayo-a.$DOMINIO/api/admin/home/banners" \
    -H "Authorization: Bearer $JWT_A" -F title=Posterior -F active=true -F sortOrder=2 \
    -F ctaText="Ver tienda" -F ctaLink=/shop -F ctaNewTab=false \
    -F textPosition=left -F textColor=light -F overlayColor=#000000 -F overlayOpacity=0 \
    -F "newImage=@$png;type=image/png" -w '\n%{http_code}')"
jq -e .image <<< "${respuesta%$'\n'*}" > /dev/null 2>&1 \
    || die "C10: no se pudo subir el banner posterior al punto (HTTP ${respuesta##*$'\n'})"
afirmar_igual "C10: la tienda A tiene dos banners" 2 \
    "$(https "ensayo-a.$DOMINIO" "https://ensayo-a.$DOMINIO/api/v1/home-banners" | jq length)"
CORREOS_ANTES="$(correos_de "$CORREO_A")"
# Que el WAL con lo posterior al punto también esté archivado: la restauración tiene que poder pasarlo y detenerse.
sql 'SELECT pg_switch_wal()' > /dev/null
(cd / && runuser -u postgres -- pgbackrest --stanza=uvopos check)

paso "C10: restauración aislada al punto, con la llave del gestor de contraseñas"
RESTAURACION=/var/lib/uvostore-restauracion/$AMBIENTE
printf '%s\n' "$LLAVE_APP" | bash "$OPS/respaldo/restaurar.sh" "$AMBIENTE" --hasta "$PUNTO" --referencia "$PUNTO" \
    --bucket-aislado "$BUCKET_AISLADO" --llave-stdin | tee "$INFORME/restaurar.txt"
cp "$RESTAURACION/acta-restauracion.md" "$INFORME/acta-restauracion.md"
grep -q 'APP_ENCRYPTION_KEY abre todos los valores cifrados' "$RESTAURACION/acta-restauracion.md" \
    || die "la sonda de descifrado no confirma la llave en la restauración"
sql_r() { (cd / && runuser -u postgres -- psql -X -At -h "$RESTAURACION/run" -p 5433 -d "uvostore_$AMBIENTE" -c "$1"); }
RESTAURADA="http://127.0.0.1:8091"

set +e
bash "$OPS/respaldo/verificar-restauracion.sh" "$AMBIENTE" --desde "$(date -u -d '-1 day' +%Y-%m-%dT%H:%M:%SZ)" \
    --conteos-origen "$INFORME/conteos-origen.txt" --imagen "$CLAVE_IMAGEN" | tee "$INFORME/verificar-restauracion.txt"
estado=${PIPESTATUS[0]}
set -e
[ "$estado" = 0 ] || die "verificar-restauracion.sh tuvo fallas:
$(grep '^FALLA' "$INFORME/verificar-restauracion.txt")"
cp "$RESTAURACION/informe-recuperacion.json" "$INFORME/informe-recuperacion.json"

afirmar_igual "C10: en la copia, la fila del restablecimiento está como en el punto (en cola)" PENDING \
    "$(sql_r "SELECT status FROM outbox_messages WHERE id = $FILA_C10")"
afirmar_igual "C10: en la copia, el banner posterior al punto no está" 1 \
    "$(curl -s -H "Host: ensayo-a.$DOMINIO" "$RESTAURADA/api/v1/home-banners" | jq length)"
# El despachador correría cada 10 s si estuviera encendido: 30 s sin un correo nuevo bastan para verlo apagado.
sleep 30
afirmar_igual "C10: la copia no reenvió el correo que ya había salido" "$CORREOS_ANTES" "$(correos_de "$CORREO_A")"
afirmar_igual "C10: la fila sigue en cola en la copia" PENDING "$(sql_r "SELECT status FROM outbox_messages WHERE id = $FILA_C10")"
afirmar_igual "C10: el informe la trae como correo incierto" true \
    "$(jq --argjson id "$FILA_C10" '[.stores[].outbox[] | select(.outboxId == $id and .classification == "INCIERTO_CORREO")] | length == 1' "$INFORME/informe-recuperacion.json")"

retenidas="$(curl -sS -X POST "$RESTAURADA/api/platform/recovery/outbox/hold" \
    -H "X-Platform-Key: $PLATFORM_KEY" -H 'X-Platform-Operator: ensayo' -H 'Content-Type: application/json' \
    -d "{\"restoredAt\":\"$PUNTO_ISO\"}" | jq -er .held)" || die "C10: POST /api/platform/recovery/outbox/hold no respondió {held: N}"
[ "$retenidas" -ge 1 ] || die "C10: la retención no tomó ninguna fila"
afirmar_igual "C10: retenida para revisión, con el motivo" "FAILED|Restaurado desde respaldo" \
    "$(sql_r "SELECT status || '|' || left(last_error, 25) FROM outbox_messages WHERE id = $FILA_C10")"
afirmar_igual "C10: y la instancia viva no se enteró" "SENT|sin-error" "$(fila_reset)"

RTO_TECNICO="$(sed -n 's/^| \*\*Duración técnica (RTO sin conciliación)\*\* | \*\*\(.*\)\*\* |$/\1/p' "$RESTAURACION/acta-restauracion.md")"
RPO_MEDIDO="$(sed -n 's/^| \*\*Pérdida respecto de la referencia (RPO)\*\* | \*\*\(.*\)\*\* |$/\1/p' "$RESTAURACION/acta-restauracion.md")"
{
    echo "punto de restauración: $PUNTO"
    echo "RTO técnico (restaurar + instancia lista): $RTO_TECNICO"
    echo "RPO medido (punto - última transacción restaurada): $RPO_MEDIDO"
} >> "$INFORME/c10.txt"
echo "OK  C10: RTO técnico $RTO_TECNICO, RPO $RPO_MEDIDO (detalle en c10.txt y acta-restauracion.md)"

paso "C10: con la llave equivocada, la sonda lo dice"
bash "$OPS/respaldo/restaurar.sh" "$AMBIENTE" --detener
openssl rand -base64 32 | bash "$OPS/respaldo/restaurar.sh" "$AMBIENTE" --hasta "$PUNTO" --llave-stdin --forzar \
    > "$INFORME/restaurar-llave-equivocada.txt"
grep -q 'APP_ENCRYPTION_KEY no abre [1-9][0-9]* valores' "$RESTAURACION/acta-restauracion.md" \
    || die "C10: con otra llave, la sonda no informa las credenciales que no abre"
set +e
bash "$OPS/respaldo/verificar-restauracion.sh" "$AMBIENTE" --desde "$PUNTO_ISO" > "$INFORME/verificar-llave-equivocada.txt"
estado=$?
set -e
afirmar_igual "C10: verificar-restauracion.sh falla con la llave equivocada" 1 "$estado"
grep -q '^FALLA  la llave NO abre' "$INFORME/verificar-llave-equivocada.txt" \
    || die "C10: verificar-restauracion.sh no dice que la llave no abre las credenciales"
echo "OK  C10: la llave equivocada se detecta al restaurar, no al primer cobro"
bash "$OPS/respaldo/restaurar.sh" "$AMBIENTE" --detener

paso "ensayo completo"
