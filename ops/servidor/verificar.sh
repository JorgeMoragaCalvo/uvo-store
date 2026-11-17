#!/usr/bin/env bash
# IMP-05. Comprueba un ambiente desplegado y deja un informe sin secretos: la evidencia de C02/C03/C04/C05/C07/C09
# que se puede sacar desde el propio servidor.
#
#   sudo bash ops/servidor/verificar.sh staging --tienda demo [opciones] | tee verificacion-staging.txt
#
# Opciones:
#   --tienda <slug>          tienda existente del ambiente (obligatoria): se prueba en <slug>.<dominio-plataforma>
#   --dominio-propio <dom>   además, un dominio propio ya dado de alta y asignado a una tienda
#   --imagen <url>           una imagen subida (C09): tiene que responder 200 con un tipo image/*
#   --reinicio               reinicia el servicio y comprueba el apagado ordenado (C02). Corta el servicio unos
#                            segundos: no correrlo con tráfico real sin avisar.
#   --por-dns                pide las URL públicas por el DNS real en lugar de forzarlas a este nginx (127.0.0.1)
#   --inseguro               no valida los certificados (solo el ensayo, con autofirmados)
#
# Sale con 0 si todo pasó y con 1 si algo falló; cada línea dice OK, FALLA o INFO.

set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
. "$DIR/comun.sh"

AMBIENTE="${1:-}"
validar_ambiente "$AMBIENTE"
shift
TIENDA="" DOMINIO_PROPIO="" IMAGEN="" REINICIO=0 POR_DNS=0 INSEGURO=0
while [ $# -gt 0 ]; do
    case "$1" in
        --tienda) TIENDA="$2"; shift 2 ;;
        --dominio-propio) DOMINIO_PROPIO="$2"; shift 2 ;;
        --imagen) IMAGEN="$2"; shift 2 ;;
        --reinicio) REINICIO=1; shift ;;
        --por-dns) POR_DNS=1; shift ;;
        --inseguro) INSEGURO=1; shift ;;
        *) die "opción desconocida: $1" ;;
    esac
done
[ -n "$TIENDA" ] || die "falta --tienda <slug>"
requerir_root
requerir_comandos curl systemctl ss sha256sum openssl runuser psql

ENV_FILE="$(archivo_env "$AMBIENTE")"
[ -f "$ENV_FILE" ] || die "$AMBIENTE no está provisionado"
SERVICIO="uvostore-$AMBIENTE"
OPT="${UVOSTORE_OPT_PREFIX}$AMBIENTE"
SRV="${UVOSTORE_SRV_PREFIX}$AMBIENTE"
PUERTO="$(valor_env "$ENV_FILE" SERVER_PORT)"
DOMINIO="$(valor_env "$ENV_FILE" PUBLIC_PLATFORM_DOMAIN)"
HOST_TIENDA="$TIENDA.$DOMINIO"
BASE="uvostore_$AMBIENTE"
PSQL_SUPERUSUARIO="${PSQL_SUPERUSUARIO:-postgres}"

FALLAS=0
ok() { echo "OK     $*"; }
falla() { echo "FALLA  $*"; FALLAS=$((FALLAS + 1)); }
dato() { echo "INFO   $*"; }
seccion() { echo; echo "## $*"; }
comprobar() { # comprobar <descripción> <comando...>
    local desc="$1"
    shift
    if "$@" > /dev/null 2>&1; then ok "$desc"; else falla "$desc"; fi
}

# curl contra el nginx de este servidor (o por DNS con --por-dns).
pub() { # pub <host> <curl args...>
    local host="$1"
    shift
    local args=(-s --max-time 15)
    [ "$INSEGURO" = 1 ] && args+=(-k)
    [ "$POR_DNS" = 1 ] || args+=(--resolve "$host:443:127.0.0.1" --resolve "$host:80:127.0.0.1")
    curl "${args[@]}" "$@"
}

sql() {
    (cd / && runuser -u "$PSQL_SUPERUSUARIO" -- psql -X -At -d "$BASE" -c "$1")
}

echo "# Verificación de UvoStore ($AMBIENTE) — $(date -u +%Y-%m-%dT%H:%M:%SZ) en $(hostname)"

# ---- Artefactos y configuración efectiva (C02, paso 7 de IMP-05) -------------------------------------------
seccion "Artefactos"
if [ -L "$OPT/current.jar" ]; then
    jar="$(readlink -f "$OPT/current.jar")"
    dato "backend: $(basename "$jar") sha256=$(sha256sum "$jar" | cut -d' ' -f1)"
else
    falla "no hay current.jar en $OPT"
fi
if [ -L "$SRV/frontend/current" ]; then
    dato "frontend: $(basename "$(readlink -f "$SRV/frontend/current")")"
else
    falla "no hay frontend publicado en $SRV/frontend/current"
fi
dato "flyway: $(sql "select version || ' ' || description from flyway_schema_history where success order by installed_rank desc limit 1" 2> /dev/null || echo '?')"
dato "java: $(java -version 2>&1 | head -n 1)"
dato "nginx: $(nginx -v 2>&1)"

seccion "Configuración efectiva (secretos enmascarados)"
grep -E '^[A-Z_]+=' "$ENV_FILE" | while IFS='=' read -r clave valor; do
    if [[ "$clave" =~ (PASSWORD|SECRET|_KEY|TOKEN|DSN) ]]; then
        if [ -n "$valor" ]; then valor="<definido, ${#valor} caracteres>"; else valor="<vacío>"; fi
    fi
    echo "       $clave=$valor"
done
dato "servicio: $(systemctl show "$SERVICIO" -p User -p MemoryMax -p CPUQuotaPerSecUSec -p TimeoutStopUSec | tr '\n' ' ')"
dato "permisos de $ENV_FILE: $(stat -c '%U:%G %a' "$ENV_FILE")"
[ "$(stat -c '%a' "$ENV_FILE")" = 640 ] && ok "archivo de entorno 640" || falla "archivo de entorno con permisos $(stat -c '%a' "$ENV_FILE"), se esperaba 640"

# ---- Proceso (C02) -----------------------------------------------------------------------------------------
seccion "Proceso"
comprobar "$SERVICIO activo" systemctl is-active --quiet "$SERVICIO"
comprobar "$SERVICIO habilitado al arranque" systemctl is-enabled --quiet "$SERVICIO"
comprobar "liveness UP" curl -fsS "http://127.0.0.1:$PUERTO/actuator/health/liveness"
comprobar "readiness UP" curl -fsS "http://127.0.0.1:$PUERTO/actuator/health/readiness"

# ---- Red privada (C03) -------------------------------------------------------------------------------------
seccion "Red privada"
direcciones_en() { ss -ltnH "sport = :$1" | awk '{ print $4 }' | sed 's/:[0-9]*$//' | sort -u; }
solo_loopback() {
    local d todas
    todas="$(direcciones_en "$1")"
    [ -n "$todas" ] || return 1
    for d in $todas; do
        case "$d" in 127.* | "[::1]" | ::1) ;; *) return 1 ;; esac
    done
}
if solo_loopback "$PUERTO"; then ok "backend ($PUERTO) escucha solo en loopback"; else falla "backend ($PUERTO) escucha en: $(direcciones_en "$PUERTO" | tr '\n' ' ')"; fi
if solo_loopback 5432; then ok "PostgreSQL (5432) escucha solo en loopback"; else falla "PostgreSQL (5432) escucha en: $(direcciones_en 5432 | tr '\n' ' ')"; fi
codigo="$(pub "$HOST_TIENDA" -o /dev/null -w '%{http_code}' "https://$HOST_TIENDA/actuator/health")"
[ "$codigo" = 404 ] && ok "/actuator/health desde el host público: 404" || falla "/actuator/health desde el host público: $codigo"
codigo="$(pub "$HOST_TIENDA" -o /dev/null -w '%{http_code}' "https://$HOST_TIENDA/actuator/prometheus")"
[ "$codigo" = 404 ] && ok "/actuator/prometheus desde el host público: 404" || falla "/actuator/prometheus desde el host público: $codigo"

# ---- Hosts desconocidos (C03) ------------------------------------------------------------------------------
seccion "Hosts desconocidos"
ajeno="verificacion-$RANDOM.invalid"
curl -s --max-time 10 -o /dev/null -H "Host: $ajeno" http://127.0.0.1/
[ $? = 52 ] && ok "http con Host ajeno: sin respuesta (444)" || falla "http con Host ajeno: respondió algo"
curl -sk --max-time 10 -o /dev/null --resolve "$ajeno:443:127.0.0.1" "https://$ajeno/"
estado=$?
[ "$estado" = 35 ] && ok "https con SNI ajeno: handshake rechazado" || falla "https con SNI ajeno: curl salió con $estado (se esperaba 35)"
inexistente="no-existe-$RANDOM.$DOMINIO"
respuesta="$(pub "$inexistente" -w '\n%{http_code}' "https://$inexistente/api/v1/store-settings")"
if [ "$(tail -n 1 <<< "$respuesta")" = 404 ] && grep -q 'Tienda no encontrada' <<< "$respuesta"; then
    ok "subdominio sin tienda: 404 «Tienda no encontrada» del backend"
else
    falla "subdominio sin tienda: $(tail -n 1 <<< "$respuesta")"
fi

# ---- La tienda, su frontend y su caché (C03/C04/C05) -------------------------------------------------------
probar_host() { # probar_host <host> <etiqueta>
    local host="$1" etiqueta="$2" cabeceras codigo
    codigo="$(pub "$host" -o /dev/null -w '%{http_code}' "http://$host/")"
    [ "$codigo" = 301 ] && ok "$etiqueta: http redirige a https" || falla "$etiqueta: http respondió $codigo"
    cabeceras="$(pub "$host" -o /dev/null -D - "https://$host/admin/login")"
    grep -q '^HTTP/[0-9.]* 200' <<< "$cabeceras" && ok "$etiqueta: recarga directa de una ruta de la SPA (200)" || falla "$etiqueta: /admin/login no respondió 200"
    grep -qi "^content-security-policy:.*frame-ancestors 'none'" <<< "$cabeceras" && ok "$etiqueta: frame-ancestors 'none'" || falla "$etiqueta: falta frame-ancestors"
    grep -qi '^x-frame-options: DENY' <<< "$cabeceras" && ok "$etiqueta: X-Frame-Options DENY" || falla "$etiqueta: falta X-Frame-Options"
    grep -qi '^strict-transport-security:' <<< "$cabeceras" && ok "$etiqueta: HSTS" || falla "$etiqueta: falta HSTS"
    grep -qi '^cache-control: no-cache' <<< "$cabeceras" && ok "$etiqueta: index.html no-cache" || falla "$etiqueta: index.html sin no-cache"
    cabeceras="$(pub "$host" -o /dev/null -D - "https://$host/api/v1/store-settings")"
    grep -q '^HTTP/[0-9.]* 200' <<< "$cabeceras" && ok "$etiqueta: la API resuelve la tienda por el Host" || falla "$etiqueta: /api/v1/store-settings no respondió 200"
    grep -qi '^cache-control:.*no-store' <<< "$cabeceras" && ok "$etiqueta: respuesta de la API no-store" || falla "$etiqueta: la API no responde no-store"
    if [ "$INSEGURO" = 0 ]; then
        local vence
        vence="$(openssl s_client -connect "127.0.0.1:443" -servername "$host" < /dev/null 2> /dev/null | openssl x509 -noout -enddate 2> /dev/null | cut -d= -f2)"
        [ -n "$vence" ] && dato "$etiqueta: certificado vence $vence" || falla "$etiqueta: no se pudo leer el certificado"
    fi
}
seccion "Tienda $HOST_TIENDA"
probar_host "$HOST_TIENDA" "$TIENDA"
if [ -n "$DOMINIO_PROPIO" ]; then
    seccion "Dominio propio $DOMINIO_PROPIO"
    probar_host "$DOMINIO_PROPIO" "$DOMINIO_PROPIO"
fi
if [ "$INSEGURO" = 0 ]; then
    if systemctl is-active --quiet certbot.timer || systemctl is-active --quiet snap.certbot.renew.timer; then
        ok "renovación automática de Certbot activa"
    else
        falla "no hay timer de renovación de Certbot activo"
    fi
    [ -x /etc/letsencrypt/renewal-hooks/deploy/uvostore-recargar-nginx ] && ok "hook de recarga de nginx tras renovar" || falla "falta el hook de recarga de nginx"
fi

# ---- Imágenes (C09) ----------------------------------------------------------------------------------------
if [ -n "$IMAGEN" ]; then
    seccion "Imagen subida"
    args=(-s --max-time 15 -o /dev/null -w '%{http_code} %{content_type}')
    [ "$INSEGURO" = 1 ] && args+=(-k)
    resultado="$(curl "${args[@]}" "$IMAGEN")"
    case "$resultado" in
        "200 image/"*) ok "imagen: $resultado" ;;
        *) falla "imagen $IMAGEN: $resultado" ;;
    esac
    [[ "$IMAGEN" == https://* ]] && ok "imagen por https" || falla "la imagen no es https"
fi

# ---- Roles de base de datos (C07) --------------------------------------------------------------------------
seccion "Base de datos"
fila="$(sql "select r.rolsuper, has_schema_privilege(r.rolname, 'public', 'CREATE'), (select count(*) from pg_tables t where t.schemaname = 'public' and t.tableowner = r.rolname) from pg_roles r where r.rolname = '${BASE}_app'")"
[ "$fila" = "f|f|0" ] && ok "${BASE}_app sin superusuario, sin CREATE y sin tablas propias" || falla "${BASE}_app: superusuario|CREATE|tablas = ${fila:-?}"
fila="$(sql "select rolsuper from pg_roles where rolname = '${BASE}_migrador'")"
[ "$fila" = f ] && ok "${BASE}_migrador sin superusuario" || falla "${BASE}_migrador: superusuario = ${fila:-?}"
fila="$(sql "select has_database_privilege('public', '$BASE', 'CONNECT')" 2> /dev/null)"
[ "$fila" = f ] && ok "nadie más que sus roles se conecta a $BASE" || falla "PUBLIC puede conectarse a $BASE"

# ---- Apagado ordenado (C02) --------------------------------------------------------------------------------
if [ "$REINICIO" = 1 ]; then
    seccion "Apagado ordenado"
    desde="$(date '+%Y-%m-%d %H:%M:%S')"
    inicio=$(date +%s)
    systemctl stop "$SERVICIO"
    dato "detenido en $(($(date +%s) - inicio)) s"
    if journalctl -u "$SERVICIO" --since "$desde" --no-pager | grep -q 'Graceful shutdown complete'; then
        ok "Spring completó el apagado ordenado"
    else
        falla "no aparece «Graceful shutdown complete» en el log"
    fi
    systemctl start "$SERVICIO"
    if esperar_readiness "$PUERTO" 180 "$SERVICIO"; then ok "vuelve a estar listo"; else falla "no volvió a quedar listo"; fi
fi

echo
if [ "$FALLAS" = 0 ]; then
    echo "# Resultado: todo OK"
else
    echo "# Resultado: $FALLAS comprobaciones fallaron"
    exit 1
fi
