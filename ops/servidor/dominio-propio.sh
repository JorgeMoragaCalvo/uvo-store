#!/usr/bin/env bash
# IMP-05 (C03). Da de alta o de baja en nginx el dominio propio de una tienda.
#
#   sudo bash ops/servidor/dominio-propio.sh alta staging www.mitienda.cl
#   sudo bash ops/servidor/dominio-propio.sh baja staging www.mitienda.cl
#
# nginx es solo la mitad. El backend resuelve la tienda por stores.domain, que se cambia por la API de plataforma;
# el orden completo (DNS, certificado, nginx, dominio en la tienda, verificación) está en docs/despliegue-backend.md,
# «Dominio propio». Aquí:
#
#   alta: obtiene el certificado con Certbot (HTTP-01: el DNS del dominio ya tiene que apuntar a este servidor;
#         default.conf responde el desafío aunque el dominio todavía no tenga server), renderiza
#         ops/nginx/dominio-propio.conf.template en /etc/nginx/uvostore/<ambiente>/dominios/<dominio>.conf y recarga.
#   baja: quita ese archivo y recarga; desde ahí el dominio cae en default.conf (444 / handshake rechazado). El
#         certificado se conserva salvo BORRAR_CERTIFICADO=1.
#
# CERTIFICADOS=autofirmados genera uno propio en lugar de Certbot: SOLO para el ensayo de CI.
# CERTBOT_EMAIL: correo de la cuenta de Let's Encrypt, si el servidor todavía no tiene una registrada.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPS="$(cd "$DIR/.." && pwd)"
# shellcheck source-path=SCRIPTDIR
. "$DIR/comun.sh"

[ $# -eq 3 ] || die "uso: $0 alta|baja <ambiente> <dominio>"
ACCION="$1"
AMBIENTE="$2"
DOMINIO="$(printf '%s' "$3" | tr '[:upper:]' '[:lower:]')"
validar_ambiente "$AMBIENTE"
requerir_root
requerir_comandos nginx systemctl openssl

# El mismo formato que acepta la API de plataforma (StoreOnboardingRequest).
[[ "$DOMINIO" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$ ]] \
    || die "'$DOMINIO' no es un dominio válido"

ENV_FILE="$(archivo_env "$AMBIENTE")"
[ -f "$ENV_FILE" ] || die "$AMBIENTE no está provisionado (sudo bash ops/servidor/provisionar.sh $AMBIENTE)"
PLATAFORMA="$(valor_env "$ENV_FILE" PUBLIC_PLATFORM_DOMAIN)"
case "$DOMINIO" in
    "$PLATAFORMA" | *."$PLATAFORMA")
        die "$DOMINIO es del dominio de la plataforma ($PLATAFORMA): ya lo cubre el comodín, no es un dominio propio" ;;
esac

DOMINIOS_DIR="$NGINX_ETC/uvostore/$AMBIENTE/dominios"
CONF="$DOMINIOS_DIR/$DOMINIO.conf"
CERTIFICADOS="${CERTIFICADOS:-letsencrypt}"

case "$ACCION" in
    alta)
        # Un dominio en dos ambientes a la vez serían dos server con el mismo nombre: nginx usaría uno y avisaría.
        otro="$(find "$NGINX_ETC/uvostore" -path "*/dominios/$DOMINIO.conf" ! -path "$CONF" 2> /dev/null | head -n 1)"
        [ -z "$otro" ] || die "$DOMINIO ya está dado de alta en otro ambiente ($otro)"

        case "$CERTIFICADOS" in
            letsencrypt)
                requerir_comandos certbot
                CERTS="/etc/letsencrypt/live/$DOMINIO"
                cuenta=(--register-unsafely-without-email)
                [ -n "${CERTBOT_EMAIL:-}" ] && cuenta=(-m "$CERTBOT_EMAIL")
                info "certificado de $DOMINIO (HTTP-01)"
                certbot certonly --webroot -w "$CERTBOT_WEBROOT" -d "$DOMINIO" --cert-name "$DOMINIO" \
                    --non-interactive --agree-tos --keep-until-expiring "${cuenta[@]}"
                ;;
            autofirmados)
                CERTS="$UVOSTORE_ETC/certs-autofirmados/$DOMINIO"
                if [ ! -f "$CERTS/fullchain.pem" ]; then
                    aviso "certificado autofirmado para $DOMINIO: solo sirve para el ensayo"
                    install -d -m 755 "$CERTS"
                    openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=$DOMINIO" \
                        -addext "subjectAltName=DNS:$DOMINIO" \
                        -keyout "$CERTS/privkey.pem" -out "$CERTS/fullchain.pem" 2> /dev/null
                    chmod 600 "$CERTS/privkey.pem"
                fi
                ;;
            *) die "CERTIFICADOS tiene que ser letsencrypt o autofirmados" ;;
        esac

        install -d -m 755 "$DOMINIOS_DIR"
        tmp="$(mktemp)"
        renderizar "$OPS/nginx/dominio-propio.conf.template" "$tmp" \
            AMBIENTE="$AMBIENTE" DOMINIO="$DOMINIO" CERTS_DOMINIO="$CERTS"
        instalar_nginx "$tmp" "$CONF"
        rm -f "$tmp"
        info "$DOMINIO servido por nginx en $AMBIENTE"
        cat << FIN
Falta en la plataforma (X-Platform-Key, desde la red de UvoPOS):
  PUT /api/platform/stores/<id>/domain            {"domain":"$DOMINIO"}
  (comprobar https://$DOMINIO/ y que muestre esa tienda)
  PUT /api/platform/stores/<id>/domain/verified   {"verified":true}
Hasta la verificación, los correos y los retornos de pago siguen usando <slug>.$PLATAFORMA.
FIN
        ;;
    baja)
        if [ -f "$CONF" ]; then
            respaldo="$(mktemp)"
            cp -p "$CONF" "$respaldo"
            rm -f "$CONF"
            if ! nginx -t 2> /dev/null; then
                mv -f "$respaldo" "$CONF"
                die "nginx -t falló sin $CONF; se dejó como estaba"
            fi
            rm -f "$respaldo"
            recargar_nginx
            info "$DOMINIO ya no lo sirve nginx en $AMBIENTE"
        else
            aviso "$DOMINIO no estaba dado de alta en $AMBIENTE"
        fi
        if [ "${BORRAR_CERTIFICADO:-}" = 1 ] && [ "$CERTIFICADOS" = letsencrypt ]; then
            certbot delete --cert-name "$DOMINIO" --non-interactive
        fi
        # La API de plataforma cambia el dominio pero hoy no lo quita (UpdateStoreDomainRequest exige uno); cerrar la
        # tienda sí lo borra. Mientras stores.domain siga con este valor, el backend lo resuelve si alguien lo apunta
        # a otro proxy: nginx ya no lo sirve, que es lo que importa desde Internet.
        echo "Si la tienda cambia de dominio: PUT /api/platform/stores/<id>/domain con el nuevo (y su alta aquí)."
        ;;
    *) die "la acción tiene que ser alta o baja" ;;
esac
