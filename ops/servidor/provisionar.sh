#!/usr/bin/env bash
# IMP-05 (C02/C03/C07). Prepara un ambiente de UvoStore en el servidor de la plataforma UvoPOS.
#
#   sudo DOMINIO_TIENDAS=tiendas-staging.uvostore.cl bash ops/servidor/provisionar.sh staging
#
# Se corre desde el directorio ops/ del mismo release que se va a desplegar (release.yml publica
# uvostore-ops-<tag>.tar.gz). Idempotente: la primera vez crea el archivo de entorno con los secretos generados y
# se detiene para que una persona complete lo que dice CAMBIAR_; las siguientes deja todo como corresponde:
#
#   1. usuario del sistema uvostore-<ambiente> y carpetas (/opt/uvostore-<ambiente>, /srv/uvostore-<ambiente>);
#   2. /etc/uvostore/<ambiente>.env (root:uvostore-<ambiente>, 640), nunca sobrescrito;
#   3. base y roles en PostgreSQL (ops/postgres/roles.sql), con las contraseñas de ese archivo;
#   4. la unidad uvostore-<ambiente>.service, habilitada (arranca con el primer despliegue);
#   5. nginx: fragmentos, default_server, el server del ambiente y el hook de renovación de Certbot.
#
# Variables (solo la primera vez, o para cambiar algo):
#   DOMINIO_TIENDAS  PUBLIC_PLATFORM_DOMAIN del ambiente. Obligatorio para crear el archivo de entorno; después se
#                    lee de ahí.
#   PUERTO_BACKEND   por omisión 8081 (staging) u 8082 (production); se escribe en el archivo de entorno.
#   CERTIFICADOS     letsencrypt (por omisión): el comodín tiene que existir en /etc/letsencrypt/live/<dominio>.
#                    autofirmados: genera uno propio. SOLO para el ensayo de CI, nunca en un ambiente de verdad.
#   HEAP_MAX, MEMORIA_MAX, CPU_MAX   límites del servicio (512m, 1G, 100%).
#   PSQL_SUPERUSUARIO  usuario del sistema con el que se corre psql como superusuario (postgres).
#
# Procedimiento completo: docs/despliegue-backend.md.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPS="$(cd "$DIR/.." && pwd)"
# shellcheck source-path=SCRIPTDIR
. "$DIR/comun.sh"

AMBIENTE="${1:-}"
validar_ambiente "$AMBIENTE"
requerir_root
requerir_comandos java nginx psql systemctl runuser useradd curl openssl sha256sum unzip install awk sed

USUARIO="uvostore-$AMBIENTE"
OPT="${UVOSTORE_OPT_PREFIX}$AMBIENTE"
SRV="${UVOSTORE_SRV_PREFIX}$AMBIENTE"
ENV_FILE="$(archivo_env "$AMBIENTE")"
CERTIFICADOS="${CERTIFICADOS:-letsencrypt}"
HEAP_MAX="${HEAP_MAX:-512m}"
MEMORIA_MAX="${MEMORIA_MAX:-1G}"
CPU_MAX="${CPU_MAX:-100%}"
PSQL_SUPERUSUARIO="${PSQL_SUPERUSUARIO:-postgres}"

# ---- 0. Versiones -------------------------------------------------------------------------------------------
version_java="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
[ -n "$version_java" ] && [ "$version_java" -ge 21 ] || die "hace falta Java 21 o posterior (se encontró '${version_java:-?}')"
version_nginx="$(nginx -v 2>&1 | sed -n 's|.*nginx/\([0-9.]*\).*|\1|p')"
# ssl_reject_handshake (default.conf) existe desde 1.19.4.
if [ "$(printf '%s\n1.19.4\n' "$version_nginx" | sort -V | head -n 1)" != "1.19.4" ]; then
    die "hace falta nginx 1.19.4 o posterior (se encontró ${version_nginx:-?})"
fi

# ---- 1. Usuario y carpetas ----------------------------------------------------------------------------------
if ! id "$USUARIO" > /dev/null 2>&1; then
    info "usuario del sistema $USUARIO"
    useradd --system --user-group --home-dir "$OPT" --no-create-home --shell /usr/sbin/nologin "$USUARIO"
fi
# El jar y el frontend son de root: el servicio los lee, no los puede cambiar.
install -d -m 755 -o root -g root "$OPT" "$OPT/releases" "$SRV" "$SRV/frontend" "$CERTBOT_WEBROOT"
install -d -m 755 -o root -g root "$UVOSTORE_ETC"

# ---- 2. Archivo de entorno ----------------------------------------------------------------------------------
# Cada @@GENERAR_...@@ recibe un valor distinto (por eso no sirve renderizar, que pone el mismo en todos).
generar_secretos() {
    local linea
    while IFS= read -r linea || [ -n "$linea" ]; do
        while [[ "$linea" == *@@GENERAR_BASE64@@* ]]; do
            linea="${linea/@@GENERAR_BASE64@@/$(openssl rand -base64 32)}"
        done
        while [[ "$linea" == *@@GENERAR_HEX@@* ]]; do
            linea="${linea/@@GENERAR_HEX@@/$(openssl rand -hex 32)}"
        done
        printf '%s\n' "$linea"
    done
}

if [ ! -f "$ENV_FILE" ]; then
    [ -n "${DOMINIO_TIENDAS:-}" ] || die "falta DOMINIO_TIENDAS para crear $ENV_FILE (el PUBLIC_PLATFORM_DOMAIN de $AMBIENTE)"
    info "creando $ENV_FILE con secretos nuevos"
    tmp="$(mktemp)"
    # Primero los secretos, después los marcadores de valores fijos.
    generar_secretos < "$DIR/uvostore.env.example" > "$tmp.secretos"
    renderizar "$tmp.secretos" "$tmp" \
        AMBIENTE="$AMBIENTE" \
        PUERTO_BACKEND="${PUERTO_BACKEND:-$(puerto_por_omision "$AMBIENTE")}" \
        DOMINIO_TIENDAS="$DOMINIO_TIENDAS"
    rm -f "$tmp.secretos"
    install -m 640 -o root -g "$USUARIO" "$tmp" "$ENV_FILE"
    rm -f "$tmp"
fi
chown root:"$USUARIO" "$ENV_FILE"
chmod 640 "$ENV_FILE"

# Lo que solo una persona puede poner (SMTP, bucket...). Hasta entonces no sigue: un servicio a medio configurar
# no arrancaría de todos modos, y así el motivo queda aquí y no en el log del servicio.
if grep -q '=CAMBIAR_' "$ENV_FILE"; then
    echo "Completa en $ENV_FILE estos valores y vuelve a correr el script:" >&2
    grep '=CAMBIAR_' "$ENV_FILE" | cut -d= -f1 | sed 's/^/  - /' >&2
    exit 2
fi

ambiente_env="$(valor_env "$ENV_FILE" APP_ENVIRONMENT)"
[ "$ambiente_env" = "$AMBIENTE" ] || die "$ENV_FILE tiene APP_ENVIRONMENT=$ambiente_env, no $AMBIENTE"
PUERTO="$(valor_env "$ENV_FILE" SERVER_PORT)"
[[ "$PUERTO" =~ ^[0-9]+$ ]] || die "SERVER_PORT no es un número en $ENV_FILE"
[ "$PUERTO" != 8080 ] || die "SERVER_PORT=8080 es el de AlfozApi en este servidor"
[ "$(valor_env "$ENV_FILE" SERVER_ADDRESS)" = 127.0.0.1 ] || aviso "SERVER_ADDRESS no es 127.0.0.1: el backend queda alcanzable sin nginx"
DOMINIO="$(valor_env "$ENV_FILE" PUBLIC_PLATFORM_DOMAIN)"
[ -n "$DOMINIO" ] || die "falta PUBLIC_PLATFORM_DOMAIN en $ENV_FILE"

# Los nombres de base y roles son fijos por ambiente: así un archivo de staging no puede apuntar a la base de
# production por error.
[ "$(valor_env "$ENV_FILE" DB_USERNAME)" = "uvostore_${AMBIENTE}_app" ] || die "DB_USERNAME tiene que ser uvostore_${AMBIENTE}_app"
[ "$(valor_env "$ENV_FILE" FLYWAY_USER)" = "uvostore_${AMBIENTE}_migrador" ] || die "FLYWAY_USER tiene que ser uvostore_${AMBIENTE}_migrador"
case "$(valor_env "$ENV_FILE" DB_URL)" in
    */uvostore_"$AMBIENTE" | */uvostore_"$AMBIENTE"\?*) ;;
    *) die "DB_URL tiene que apuntar a la base uvostore_$AMBIENTE" ;;
esac
CLAVE_APP="$(valor_env "$ENV_FILE" DB_PASSWORD)"
CLAVE_MIGRADOR="$(valor_env "$ENV_FILE" FLYWAY_PASSWORD)"
for clave in "$CLAVE_APP" "$CLAVE_MIGRADOR"; do
    # Van entre comillas simples a psql: sin comillas ni barras invertidas.
    [[ "$clave" =~ ^[A-Za-z0-9._~+/=-]{16,}$ ]] || die "DB_PASSWORD y FLYWAY_PASSWORD: 16 caracteres o más, sin comillas ni espacios"
done
POOL="$(valor_env "$ENV_FILE" DB_POOL_MAX)"
POOL="${POOL:-10}"
[[ "$POOL" =~ ^[0-9]+$ ]] || die "DB_POOL_MAX no es un número"

# ---- 3. PostgreSQL ------------------------------------------------------------------------------------------
info "base uvostore_$AMBIENTE y roles (ops/postgres/roles.sql)"
{
    printf '\\set ambiente %s\n' "$AMBIENTE"
    printf "\\\\set clave_migrador '%s'\n" "$CLAVE_MIGRADOR"
    printf "\\\\set clave_app '%s'\n" "$CLAVE_APP"
    printf '\\set limite_app %s\n' "$((POOL + 2))"
    cat "$OPS/postgres/roles.sql"
} | (cd / && runuser -u "$PSQL_SUPERUSUARIO" -- psql -X -q -v ON_ERROR_STOP=1 -d postgres)

# ---- 4. systemd ---------------------------------------------------------------------------------------------
info "servicio uvostore-$AMBIENTE"
tmp="$(mktemp)"
renderizar "$DIR/uvostore.service.template" "$tmp" \
    AMBIENTE="$AMBIENTE" JAVA="$(readlink -f "$(command -v java)")" \
    HEAP_MAX="$HEAP_MAX" MEMORIA_MAX="$MEMORIA_MAX" CPU_MAX="$CPU_MAX"
install -m 644 "$tmp" "/etc/systemd/system/uvostore-$AMBIENTE.service"
rm -f "$tmp"
systemctl daemon-reload
systemctl enable "uvostore-$AMBIENTE.service" > /dev/null 2>&1

# ---- 5. nginx -----------------------------------------------------------------------------------------------
info "nginx"
install -d -m 755 "$NGINX_ETC/uvostore" "$NGINX_ETC/uvostore/$AMBIENTE" "$NGINX_ETC/uvostore/$AMBIENTE/dominios"
install -m 644 "$OPS/nginx/uvostore-locations.conf" "$OPS/nginx/security-headers.conf" "$NGINX_ETC/uvostore/"

case "$CERTIFICADOS" in
    autofirmados)
        CERTS="$UVOSTORE_ETC/certs-autofirmados/$DOMINIO"
        if [ ! -f "$CERTS/fullchain.pem" ]; then
            aviso "certificado autofirmado para *.$DOMINIO: solo sirve para el ensayo"
            install -d -m 755 "$CERTS"
            openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=$DOMINIO" \
                -addext "subjectAltName=DNS:$DOMINIO,DNS:*.$DOMINIO" \
                -keyout "$CERTS/privkey.pem" -out "$CERTS/fullchain.pem" 2> /dev/null
            chmod 600 "$CERTS/privkey.pem"
        fi
        ;;
    letsencrypt)
        CERTS="/etc/letsencrypt/live/$DOMINIO"
        if [ ! -f "$CERTS/fullchain.pem" ]; then
            die "falta el certificado comodín de $DOMINIO en $CERTS. Se emite con el desafío DNS-01 del proveedor
de DNS (docs/despliegue-backend.md, «Certificados»), por ejemplo:
  certbot certonly --dns-<proveedor> -d '$DOMINIO' -d '*.$DOMINIO'
y después se vuelve a correr este script."
        fi
        ;;
    *) die "CERTIFICADOS tiene que ser letsencrypt o autofirmados" ;;
esac

# default_server: uno solo por puerto en todo nginx. Si ya hay otro (el sites-enabled/default de Ubuntu, o uno del
# POS), decidir cuál queda es de operaciones.
DEFAULT_CONF="$NGINX_ETC/conf.d/uvostore-default.conf"
otros="$(grep -rlE '^[^#]*default_server' "$NGINX_ETC/conf.d" "$NGINX_ETC/sites-enabled" 2> /dev/null | grep -vxF "$DEFAULT_CONF" || true)"
if [ -n "$otros" ]; then
    die "ya hay un default_server en: $otros
ops/nginx/default.conf corta los hosts desconocidos (C03) y necesita ser el único. Quita o ajusta ese
default_server (en Ubuntu, el sitio de ejemplo: rm /etc/nginx/sites-enabled/default) y vuelve a correr."
fi
instalar_nginx "$OPS/nginx/default.conf" "$DEFAULT_CONF"

tmp="$(mktemp)"
renderizar "$OPS/nginx/uvostore.conf.template" "$tmp" \
    AMBIENTE="$AMBIENTE" DOMINIO_TIENDAS="$DOMINIO" PUERTO_BACKEND="$PUERTO" CERTS_TIENDAS="$CERTS"
instalar_nginx "$tmp" "$NGINX_ETC/conf.d/uvostore-$AMBIENTE.conf"
rm -f "$tmp"

# Certbot renueva solo (su timer) pero no recarga nginx: sin esto, nginx seguiría sirviendo el certificado viejo
# hasta el próximo reinicio, y vencería igual.
if [ -d /etc/letsencrypt ]; then
    install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
    printf '#!/bin/sh\n# IMP-05: recargar nginx después de renovar un certificado.\nsystemctl reload nginx\n' \
        > /etc/letsencrypt/renewal-hooks/deploy/uvostore-recargar-nginx
    chmod 755 /etc/letsencrypt/renewal-hooks/deploy/uvostore-recargar-nginx
fi

info "listo: $AMBIENTE provisionado"
cat << FIN
Siguiente:
  sudo bash ops/servidor/desplegar-backend.sh uvo-store-<tag>.jar $AMBIENTE SHA256SUMS
  sudo bash ops/nginx/publicar-frontend.sh frontend-dist-<tag>-$AMBIENTE.zip $SRV/frontend SHA256SUMS
  sudo bash ops/servidor/verificar.sh $AMBIENTE --tienda <slug>
FIN
