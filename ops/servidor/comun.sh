#!/usr/bin/env bash
# IMP-05. Funciones compartidas por los scripts de ops/servidor/. Se carga con "source", no se ejecuta.
#
# Convenciones de todos los scripts:
#   - el ambiente es staging o production, y todo lo que es suyo lleva ese nombre (usuario, servicio, base,
#     carpetas, upstream), así que los dos conviven en el servidor sin compartir nada;
#   - se pueden volver a correr: lo que ya está como corresponde no se toca;
#   - ninguno imprime un secreto.

# Raíces, sobrescribibles solo para probar los scripts fuera de un servidor (publicar-frontend.test.sh hace lo
# mismo con su base). En el servidor no se definen.
: "${UVOSTORE_ETC:=/etc/uvostore}"
: "${UVOSTORE_OPT_PREFIX:=/opt/uvostore-}"
: "${UVOSTORE_SRV_PREFIX:=/srv/uvostore-}"
: "${NGINX_ETC:=/etc/nginx}"
: "${CERTBOT_WEBROOT:=/var/www/certbot}"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

info() {
    echo "==> $*"
}

aviso() {
    echo "AVISO: $*" >&2
}

validar_ambiente() {
    case "${1:-}" in
        staging | production) ;;
        *) die "el ambiente tiene que ser staging o production (se recibió '${1:-}')" ;;
    esac
}

requerir_root() {
    [ "$(id -u)" -eq 0 ] || die "hay que correrlo como root (sudo)"
}

requerir_comandos() {
    local falta=()
    for c in "$@"; do
        command -v "$c" > /dev/null 2>&1 || falta+=("$c")
    done
    [ ${#falta[@]} -eq 0 ] || die "faltan comandos: ${falta[*]}"
}

archivo_env() {
    echo "$UVOSTORE_ETC/$1.env"
}

# El valor de una variable del archivo de entorno (formato EnvironmentFile: KEY=valor, sin comillas). La última
# aparición gana, como en systemd. Vacío si no está.
valor_env() {
    local archivo="$1" clave="$2"
    sed -n "s/^${clave}=//p" "$archivo" | tail -n 1
}

# Cambia (o agrega) una variable del archivo de entorno sin tocar el resto ni los permisos.
fijar_env() {
    local archivo="$1" clave="$2" valor="$3" tmp
    tmp="$(mktemp "$archivo.XXXXXX")"
    if grep -q "^${clave}=" "$archivo"; then
        awk -v k="$clave" -v v="$valor" 'BEGIN { p = k "=" } index($0, p) == 1 { print p v; next } { print }' \
            "$archivo" > "$tmp"
    else
        cat "$archivo" > "$tmp"
        printf '%s=%s\n' "$clave" "$valor" >> "$tmp"
    fi
    chmod --reference="$archivo" "$tmp" 2> /dev/null || true
    chown --reference="$archivo" "$tmp" 2> /dev/null || true
    mv -f "$tmp" "$archivo"
}

# El puerto del backend por omisión. Nunca 8080: es el de AlfozApi en el mismo servidor.
puerto_por_omision() {
    case "$1" in
        staging) echo 8081 ;;
        production) echo 8082 ;;
    esac
}

# renderizar <plantilla> <destino> CLAVE=valor... : reemplaza cada @@CLAVE@@ y falla si queda alguno sin
# reemplazar. Solo sed: los $ de nginx y de systemd quedan como están.
renderizar() {
    local plantilla="$1" destino="$2" expr=() par clave valor
    shift 2
    for par in "$@"; do
        clave="${par%%=*}"
        valor="${par#*=}"
        # Escapar lo que sed interpreta en el reemplazo, con | de separador.
        valor="$(printf '%s' "$valor" | sed -e 's/[\\|&]/\\&/g')"
        expr+=(-e "s|@@${clave}@@|${valor}|g")
    done
    sed "${expr[@]}" "$plantilla" > "$destino"
    if grep -q '@@[A-Z_]*@@' "$destino"; then
        die "$(basename "$plantilla"): quedaron marcadores sin reemplazar: $(grep -o '@@[A-Z_]*@@' "$destino" | sort -u | tr '\n' ' ')"
    fi
}

# Instala un archivo de configuración de nginx y recarga, o deja el anterior si nginx -t no lo acepta.
# instalar_nginx <nuevo> <destino>
instalar_nginx() {
    local nuevo="$1" destino="$2" respaldo=""
    if [ -f "$destino" ]; then
        respaldo="$(mktemp)"
        cp -p "$destino" "$respaldo"
    fi
    install -m 644 "$nuevo" "$destino"
    if ! nginx -t 2> /tmp/uvostore-nginx-t.log; then
        cat /tmp/uvostore-nginx-t.log >&2
        if [ -n "$respaldo" ]; then
            mv -f "$respaldo" "$destino"
        else
            rm -f "$destino"
        fi
        die "nginx -t rechazó $destino; se dejó la configuración anterior"
    fi
    [ -n "$respaldo" ] && rm -f "$respaldo"
    recargar_nginx
}

recargar_nginx() {
    if systemctl is-active --quiet nginx; then
        systemctl reload nginx
    else
        systemctl start nginx
    fi
}

# Espera a que el backend diga que está listo. readiness = proceso arriba y base alcanzable (PROD-15a).
# esperar_readiness <puerto> <segundos> <servicio>
esperar_readiness() {
    local puerto="$1" limite="$2" servicio="$3" i=0
    while [ "$i" -lt "$limite" ]; do
        if curl -fsS -o /dev/null "http://127.0.0.1:${puerto}/actuator/health/readiness" 2> /dev/null; then
            return 0
        fi
        if systemctl is-failed --quiet "$servicio"; then
            return 1
        fi
        sleep 2
        i=$((i + 2))
    done
    return 1
}
