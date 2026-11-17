#!/usr/bin/env bash
# IMP-05 (paso 7). Ensayo limpio: construye staging desde cero con los scripts de ops/ en una máquina Ubuntu nueva
# y comprueba lo que esos scripts prometen. Lo corre el job «ensayo-servidor» de .github/workflows/ci.yml; se puede
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

# ---- Verificación final, la misma que en el servidor real ----------------------------------------------------
paso "verificar.sh"
set +e
bash "$SERVIDOR/verificar.sh" "$AMBIENTE" --tienda ensayo-a --dominio-propio "$PROPIO_NUEVO" --imagen "$IMAGEN"     --reinicio --inseguro | tee "$INFORME/verificacion-staging.txt"
estado=${PIPESTATUS[0]}
set -e
afirmar_igual "verificar.sh sin fallas" 0 "$estado"

paso "ensayo completo"
