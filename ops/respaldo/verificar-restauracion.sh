#!/usr/bin/env bash
# IMP-09 (C10, §11.2). Comprueba una restauración aislada (restaurar.sh) y saca el informe de conciliación, antes de
# decidir nada. No escribe en la base restaurada ni habla con el POS; a las pasarelas solo les pregunta el estado.
#
#   sudo bash ops/respaldo/verificar-restauracion.sh <ambiente> --desde '<fecha ISO-8601>' [opciones]
#   sudo bash ops/respaldo/verificar-restauracion.sh <ambiente> --conteos [--puerto-pg <n>] > conteos.txt
#
# Opciones:
#   --desde <fecha>          desde cuándo comparar con las pasarelas lo que se movió (el informe suma los pendientes de
#                            cualquier fecha). Típico: el punto de restauración menos los días en que se pudo devolver.
#   --conteos-origen <arch>  los conteos tomados del clúster vivo (o del ensayo) antes del incidente, para compararlos
#   --imagen <clave>         una clave del bucket aislado que tiene que estar (head-object)
#   --directorio, --puerto-pg, --puerto-app: los mismos de restaurar.sh
#
# --conteos imprime los conteos por tienda (catálogo, pedidos, reembolsos, outbox, stock) de la base que se le indique
# (por omisión el clúster vivo, puerto 5432): es lo que se guarda antes de un ensayo para comparar.
#
# Sale con 1 si algo de lo que se comprueba falla. El informe queda en <directorio>/informe-recuperacion.json, y los
# hallazgos que piden una decisión se imprimen al final.

set -euo pipefail

DIR_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPS="$(cd "$DIR_SCRIPTS/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/../servidor
. "$OPS/servidor/comun.sh"

[ $# -ge 1 ] || die "uso: $0 <ambiente> (--desde '<fecha>' | --conteos) [opciones]"
AMBIENTE="$1"
shift
validar_ambiente "$AMBIENTE"
requerir_root

DESDE="" CONTEOS=no CONTEOS_ORIGEN="" IMAGEN="" PUERTO_PG=""
DIRECTORIO="/var/lib/uvostore-restauracion/$AMBIENTE"
case "$AMBIENTE" in staging) PUERTO_APP=8091 ;; production) PUERTO_APP=8092 ;; esac
while [ $# -gt 0 ]; do
    case "$1" in
        --desde) DESDE="$2"; shift 2 ;;
        --conteos) CONTEOS=si; shift ;;
        --conteos-origen) CONTEOS_ORIGEN="$2"; shift 2 ;;
        --imagen) IMAGEN="$2"; shift 2 ;;
        --directorio) DIRECTORIO="$2"; shift 2 ;;
        --puerto-pg) PUERTO_PG="$2"; shift 2 ;;
        --puerto-app) PUERTO_APP="$2"; shift 2 ;;
        *) die "opción desconocida: $1" ;;
    esac
done
BASE="uvostore_$AMBIENTE"

# Los conteos son la huella funcional de la base: lo que un cliente o un operador vería distinto si faltara algo.
conteos() { # conteos <args de psql para llegar a la base>
    (cd / && runuser -u postgres -- psql -X -At -F ' ' "$@" -d "$BASE" -v ON_ERROR_STOP=1 -c "
        SELECT 'tiendas', count(*) FROM stores
        UNION ALL SELECT 'productos', count(*) FROM products
        UNION ALL SELECT 'stock_total', COALESCE(sum(stock), 0) FROM products
        UNION ALL SELECT 'ordenes', count(*) FROM orders
        UNION ALL SELECT 'ordenes_pagadas', count(*) FROM orders WHERE payment_status IN ('PAID', 'REFUNDED')
        UNION ALL SELECT 'reembolsos', count(*) FROM order_refunds
        UNION ALL SELECT 'reembolsos_pendientes', count(*) FROM order_refunds WHERE status = 'PENDING'
        UNION ALL SELECT 'outbox_' || lower(status), count(*) FROM outbox_messages GROUP BY status
        ORDER BY 1")
}

if [ "$CONTEOS" = si ]; then
    if [ -n "$PUERTO_PG" ]; then
        conteos -h "$DIRECTORIO/run" -p "$PUERTO_PG"
    else
        conteos
    fi
    exit 0
fi

[ -n "$DESDE" ] || die "falta --desde '<fecha ISO-8601>'"
requerir_comandos jq curl
ENV_R="$DIRECTORIO/uvostore.env"
[ -f "$ENV_R" ] || die "no hay una restauración en $DIRECTORIO (restaurar.sh)"
PUERTO_PG="${PUERTO_PG:-$(valor_env "$ENV_R" DB_URL | sed -n 's|.*:\([0-9]*\)/.*|\1|p')}"
APP="http://127.0.0.1:$PUERTO_APP"
fallas=0
ok() { echo "OK     $*"; }
falla() { echo "FALLA  $*"; fallas=$((fallas + 1)); }

# ---- La instancia ------------------------------------------------------------------------------------------------
if curl -fsS -o /dev/null "$APP/actuator/health/readiness"; then ok "readiness"; else falla "readiness"; fi

# Lo que tiene efectos responde 503: sin Host ni cuerpo, el filtro corta antes de resolver la tienda.
codigo="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$APP/api/v1/checkout" -H 'Content-Type: application/json' -d '{}')"
[ "$codigo" = 503 ] && ok "el checkout responde 503 (modo recuperación)" || falla "el checkout responde $codigo, no 503"
codigo="$(curl -s -o /dev/null -w '%{http_code}' "$APP/api/v1/webpay/return?token_ws=x")"
[ "$codigo" = 503 ] && ok "el retorno de Webpay responde 503" || falla "el retorno de Webpay responde $codigo, no 503"

TOKEN_METRICAS="$(valor_env "$ENV_R" METRICS_TOKEN)"
if [ -n "$TOKEN_METRICAS" ]; then
    metricas="$(curl -fsS "$APP/actuator/prometheus" -H "Authorization: Bearer $TOKEN_METRICAS" || true)"
    modo="$(sed -n 's/^uvostore_recovery_mode[^ ]* \([0-9.]*\)$/\1/p' <<< "$metricas" | head -n 1)"
    sin_abrir="$(sed -n 's/^uvostore_credentials_decrypt_failures[^ ]* \([0-9.]*\)$/\1/p' <<< "$metricas" | head -n 1)"
    [ "${modo%.*}" = 1 ] && ok "uvostore_recovery_mode = 1" || falla "uvostore_recovery_mode = '${modo:-sin dato}'"
    [ "${sin_abrir%.*}" = 0 ] && ok "la llave abre todas las credenciales (uvostore_credentials_decrypt_failures = 0)" \
        || falla "la llave NO abre ${sin_abrir:-?} credenciales: es la equivocada o están dañadas"
else
    echo "AVISO  sin METRICS_TOKEN: el modo y la sonda de descifrado se leen en journalctl"
fi

# ---- La base -----------------------------------------------------------------------------------------------------
actuales="$(conteos -h "$DIRECTORIO/run" -p "$PUERTO_PG")"
echo "$actuales" > "$DIRECTORIO/conteos-restaurados.txt"
if [ -n "$CONTEOS_ORIGEN" ]; then
    if diff <(sort "$CONTEOS_ORIGEN") <(sort <<< "$actuales") > "$DIRECTORIO/conteos.diff"; then
        ok "los conteos coinciden con $CONTEOS_ORIGEN"
    else
        falla "los conteos difieren de $CONTEOS_ORIGEN ($DIRECTORIO/conteos.diff):"
        sed 's/^/       /' "$DIRECTORIO/conteos.diff"
    fi
else
    echo "       conteos (sin origen con qué comparar):"
    sed 's/^/         /' <<< "$actuales"
fi

if [ -n "$IMAGEN" ]; then
    endpoint="$(valor_env "$UVOSTORE_ETC/respaldo.env" RESPALDO_AWS_ENDPOINT_URL)"
    bucket="$(valor_env "$ENV_R" APP_STORAGE_S3_BUCKET)"
    if AWS_ACCESS_KEY_ID="$(valor_env "$UVOSTORE_ETC/respaldo.env" RESPALDO_S3_KEY)" \
        AWS_SECRET_ACCESS_KEY="$(valor_env "$UVOSTORE_ETC/respaldo.env" RESPALDO_S3_KEY_SECRET)" \
        AWS_DEFAULT_REGION="$(valor_env "$UVOSTORE_ETC/respaldo.env" RESPALDO_S3_REGION)" \
        aws ${endpoint:+--endpoint-url "$endpoint"} s3api head-object --bucket "$bucket" --key "$IMAGEN" > /dev/null 2>&1; then
        ok "la imagen $IMAGEN está en el bucket aislado $bucket"
    else
        falla "la imagen $IMAGEN no está en el bucket aislado $bucket"
    fi
fi

# ---- Informe de conciliación -------------------------------------------------------------------------------------
INFORME="$DIRECTORIO/informe-recuperacion.json"
codigo="$(curl -sS -o "$INFORME" -w '%{http_code}' -G "$APP/api/platform/recovery/report" \
    --data-urlencode "from=$(date -u -d "$DESDE" +%Y-%m-%dT%H:%M:%SZ)" \
    -H "X-Platform-Key: $(valor_env "$ENV_R" PLATFORM_API_KEY)" -H 'X-Platform-Operator: restauracion')"
if [ "$codigo" = 200 ]; then
    chmod 640 "$INFORME"
    ok "informe de recuperación: $INFORME"
    jq -r '"       truncado: \(.truncated)", "       hallazgos que piden decisión:",
           (if (.attention | length) == 0 then "         ninguno" else (.attention | to_entries[] | "         \(.key): \(.value)") end)' \
        "$INFORME"
else
    falla "el informe de recuperación respondió $codigo"
fi

echo
if [ "$fallas" -gt 0 ]; then
    echo "$fallas comprobaciones fallaron"
    exit 1
fi
echo "Restauración comprobada. Siguiente: conciliar cada hallazgo y retener el outbox (docs/recuperacion.md)."
