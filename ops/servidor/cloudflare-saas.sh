#!/usr/bin/env bash
# Activa o desactiva en nginx el modo Cloudflare for SaaS: los dominios propios de las tiendas llegan a través de
# Cloudflare (que pone el certificado de cada cliente) en vez de con un certificado por dominio (dominio-propio.sh).
#
#   sudo bash ops/servidor/cloudflare-saas.sh activar production /etc/ssl/uvostore/origen.pem /etc/ssl/uvostore/origen.key
#   sudo bash ops/servidor/cloudflare-saas.sh desactivar
#
# activar:    instala ops/nginx/cloudflare-saas.conf.template como /etc/nginx/conf.d/uvostore-default.conf en lugar
#             de default.conf, con los rangos de ops/nginx/cloudflare-ips.txt, y recarga. Si nginx -t no lo acepta
#             deja la configuración anterior (instalar_nginx).
# desactivar: vuelve a default.conf (444 / handshake rechazado para todo host que no tenga server).
#
# Un solo ambiente atiende los dominios de Cloudflare: el server por omisión del 443 es uno por servidor.
# provisionar.sh reinstala default.conf: después de volver a provisionar, volver a correr "activar".
#
# Lo que no hace este script (está en docs/despliegue-backend.md, «Dominios propios con Cloudflare for SaaS»): el
# fallback origin y el modo SSL en Cloudflare, el certificado de origen, ni el firewall.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPS="$(cd "$DIR/.." && pwd)"
# shellcheck source-path=SCRIPTDIR
. "$DIR/comun.sh"

ACCION="${1:-}"
DEFAULT_CONF="$NGINX_ETC/conf.d/uvostore-default.conf"

# Las líneas útiles de cloudflare-ips.txt, validadas: una red mal escrita haría fallar nginx -t con un mensaje
# menos claro, y una vacía dejaría el filtro sin rangos.
redes_cloudflare() {
    local lista="$OPS/nginx/cloudflare-ips.txt" redes
    redes="$(grep -v '^[[:space:]]*#' "$lista" | tr -d '[:blank:]' | sed '/^$/d')"
    [ -n "$redes" ] || die "$lista no tiene ninguna red"
    while IFS= read -r red; do
        [[ "$red" =~ ^[0-9a-fA-F:.]+/[0-9]{1,3}$ ]] || die "$lista: '$red' no es una red en formato CIDR"
    done <<< "$redes"
    printf '%s\n' "$redes"
}

case "$ACCION" in
    activar)
        [ $# -eq 4 ] || die "uso: $0 activar <ambiente> <certificado de origen> <llave de origen>"
        AMBIENTE="$2"
        CERT="$3"
        LLAVE="$4"
        validar_ambiente "$AMBIENTE"
        requerir_root
        requerir_comandos nginx systemctl
        [ -f "$CERT" ] || die "no existe el certificado $CERT"
        [ -f "$LLAVE" ] || die "no existe la llave $LLAVE"
        [ -f "$NGINX_ETC/conf.d/uvostore-$AMBIENTE.conf" ] \
            || die "$AMBIENTE no está provisionado en nginx (sudo bash ops/servidor/provisionar.sh $AMBIENTE)"

        REDES="$(redes_cloudflare)"
        install -d -m 755 "$NGINX_ETC/uvostore"
        # El mismo listado en los dos formatos que pide nginx: el bloque geo (red 1;) y real_ip (set_real_ip_from).
        printf '%s\n' "$REDES" | sed 's/$/ 1;/' > "$NGINX_ETC/uvostore/cloudflare-geo.conf"
        printf '%s\n' "$REDES" | sed 's/^/set_real_ip_from /; s/$/;/' > "$NGINX_ETC/uvostore/cloudflare-realip.conf"
        chmod 644 "$NGINX_ETC/uvostore/cloudflare-geo.conf" "$NGINX_ETC/uvostore/cloudflare-realip.conf"

        tmp="$(mktemp)"
        trap 'rm -f "$tmp"' EXIT
        renderizar "$OPS/nginx/cloudflare-saas.conf.template" "$tmp" \
            AMBIENTE="$AMBIENTE" CERT_ORIGEN="$CERT" LLAVE_ORIGEN="$LLAVE"
        instalar_nginx "$tmp" "$DEFAULT_CONF"
        info "Cloudflare for SaaS activo: el 443 por omisión atiende a $AMBIENTE, solo desde las $(printf '%s\n' "$REDES" | wc -l) redes de Cloudflare"
        ;;
    desactivar)
        [ $# -eq 1 ] || die "uso: $0 desactivar"
        requerir_root
        requerir_comandos nginx systemctl
        instalar_nginx "$OPS/nginx/default.conf" "$DEFAULT_CONF"
        info "Cloudflare for SaaS desactivado: vuelve default.conf (hosts sin server rechazados)"
        ;;
    *)
        die "uso: $0 activar <ambiente> <certificado> <llave> | desactivar"
        ;;
esac
