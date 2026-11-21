#!/usr/bin/env bash
# IMP-09 (C19, §11.1). Ensayo de migración y vuelta atrás: la versión anterior (la etiqueta previa) con datos, la
# candidata migrando encima, el binario anterior otra vez sobre el esquema migrado, y la restauración del respaldo de
# antes de migrar. Clasifica el paso de una a otra:
#
#   ROLLBACK_BINARIO        el jar anterior arranca sobre el esquema migrado: volver atrás es reactivar el jar
#                           (desplegar-backend.sh --activar). Ojo con lo semántico: ver la tabla de docs/recuperacion.md.
#   RESTAURAR_Y_CONCILIAR   el jar anterior no arranca: volver atrás es restaurar el respaldo de antes de migrar y
#                           conciliar lo que pasó después (docs/recuperacion.md). La alternativa, si se prefiere, es
#                           corregir hacia adelante con una versión nueva (CORRECCION_ADELANTE), que no se ensaya aquí.
#
# Lo corre el job «migracion-rollback» de .github/workflows/ci.yml. Se puede repetir en una VM desechable con
# PostgreSQL; NUNCA en el servidor de la plataforma (crea y borra bases).
#
#   sudo bash ops/servidor/ensayo/migracion-rollback.sh <jar-anterior> <jar-candidato> <carpeta-informe>
#
# Los jars corren con APP_ENVIRONMENT=development y un solo rol de base: lo que se ensaya es el esquema, no las reglas
# de producción (eso lo hace ensayo.sh). El respaldo es un pg_dump de la base; la recuperación a un punto en el tiempo
# del clúster la ensaya ensayo.sh (paso C10).

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVIDOR="$(cd "$DIR/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/..
. "$SERVIDOR/comun.sh"

[ $# -eq 3 ] || die "uso: $0 <jar-anterior> <jar-candidato> <carpeta-informe>"
ANTERIOR="$(readlink -f "$1")"
CANDIDATO="$(readlink -f "$2")"
INFORME="$3"
requerir_root
requerir_comandos java psql pg_dump pg_restore curl jq unzip
mkdir -p "$INFORME"

PUERTO=18090
BASE=uvostore_migracion
BASE_R=uvostore_migracion_restaurada
ROL=uvostore_migracion
CLAVE_ROL="$(openssl rand -hex 16)"
PLATAFORMA="$(openssl rand -hex 24)"
TRABAJO="$(mktemp -d)"
chmod 755 "$TRABAJO"
sql() { (cd / && runuser -u postgres -- psql -X -At -v ON_ERROR_STOP=1 "$@"); }
paso() { echo; echo "######## $*"; }

migracion_del_jar() {
    unzip -Z1 "$1" | sed -n 's|.*db/migration/V\([0-9]*\)__.*|\1|p' | sort -n | tail -n 1
}
V_ANTERIOR="$(migracion_del_jar "$ANTERIOR")"
V_CANDIDATO="$(migracion_del_jar "$CANDIDATO")"
echo "anterior:  $(basename "$ANTERIOR") (hasta V$V_ANTERIOR)"
echo "candidato: $(basename "$CANDIDATO") (hasta V$V_CANDIDATO)"

PID=""
arrancar() { # arrancar <jar> <base> <log> : deja el proceso en $PID
    (
        cd "$TRABAJO"
        # exec: el PID que se guarda es el de java, y detener() lo termina de verdad.
        exec env -i PATH="$PATH" HOME="$TRABAJO" \
            APP_ENVIRONMENT=development SERVER_ADDRESS=127.0.0.1 SERVER_PORT="$PUERTO" \
            DB_URL="jdbc:postgresql://127.0.0.1:5432/$2" DB_USERNAME="$ROL" DB_PASSWORD="$CLAVE_ROL" \
            FLYWAY_USER="" FLYWAY_PASSWORD="" \
            JWT_SECRET="$(openssl rand -base64 48)" APP_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
            PLATFORM_API_KEY="$PLATAFORMA" PLATFORM_ALLOWED_NETWORKS=127.0.0.1/32 \
            APP_STORAGE_DRIVER=local APP_UPLOAD_DIR="$TRABAJO/uploads" MAIL_HOST="" SENTRY_DSN="" \
            RATE_LIMIT_ENABLED=false OUTBOX_SCHEDULER_ENABLED=false RECONCILIATION_ENABLED=false \
            ABANDONED_ORDERS_ENABLED=false STOCK_RECOVERY_ENABLED=false POS_ENABLED=false \
            java -Xmx512m -jar "$1" > "$3" 2>&1
    ) &
    PID=$!
}
listo() { # listo <segundos> : 0 si responde la readiness (o health, en versiones sin sondas); 1 si el proceso murió
    local i=0
    while [ "$i" -lt "$1" ]; do
        curl -fs -o /dev/null "http://127.0.0.1:$PUERTO/actuator/health/readiness" && return 0
        curl -fs -o /dev/null "http://127.0.0.1:$PUERTO/actuator/health" && return 0
        kill -0 "$PID" 2> /dev/null || return 1
        sleep 2
        i=$((i + 2))
    done
    return 1
}
detener() {
    [ -n "$PID" ] || return 0
    kill "$PID" 2> /dev/null || true
    wait "$PID" 2> /dev/null || true
    PID=""
}
trap detener EXIT
conteos() { # conteos <base>
    sql -d "$1" -F ' ' -c "
        SELECT 'stores', count(*) FROM stores
        UNION ALL SELECT 'users', count(*) FROM users
        UNION ALL SELECT 'settings', count(*) FROM settings
        UNION ALL SELECT 'products', count(*) FROM products
        UNION ALL SELECT 'orders', count(*) FROM orders
        ORDER BY 1"
}
version_de() { sql -d "$1" -c "SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+\$'"; }

# ---- Base limpia -------------------------------------------------------------------------------------------------
sql -c "DROP DATABASE IF EXISTS $BASE" -c "DROP DATABASE IF EXISTS $BASE_R" -c "DROP ROLE IF EXISTS $ROL" > /dev/null
sql -c "CREATE ROLE $ROL LOGIN PASSWORD '$CLAVE_ROL'" -c "CREATE DATABASE $BASE OWNER $ROL" > /dev/null

# ---- 1. Versión anterior con datos -------------------------------------------------------------------------------
paso "1. $(basename "$ANTERIOR") sobre una base vacía"
arrancar "$ANTERIOR" "$BASE" "$INFORME/1-anterior.log"
listo 240 || die "la versión anterior no arrancó sobre una base vacía (1-anterior.log)"
# Una tienda por la API de plataforma, si esa versión la tiene; si no, quedan los datos que siembran las migraciones.
codigo="$(curl -s -o "$INFORME/1-tienda.json" -w '%{http_code}' -X POST "http://127.0.0.1:$PUERTO/api/platform/stores" \
    -H "X-Platform-Key: $PLATAFORMA" -H 'X-Platform-Operator: ensayo' -H 'Content-Type: application/json' \
    -d '{"slug":"migracion","domain":"","storeName":"Tienda migración","adminName":"Ensayo","adminEmail":"migracion@ensayo.test"}')"
case "$codigo" in
    2*) DATOS="una tienda creada por la API de plataforma y lo que siembran las migraciones" ;;
    *) DATOS="solo lo que siembran las migraciones (la API de plataforma respondió $codigo)" ;;
esac
echo "datos: $DATOS"
detener
conteos "$BASE" > "$INFORME/conteos-antes.txt"
cat "$INFORME/conteos-antes.txt"
[ "$(version_de "$BASE")" = "$V_ANTERIOR" ] || die "la base no quedó en V$V_ANTERIOR"

# ---- 2. Respaldo de antes de migrar ------------------------------------------------------------------------------
paso "2. respaldo (pg_dump) antes de migrar"
(cd / && runuser -u postgres -- pg_dump -Fc -d "$BASE") > "$TRABAJO/antes.dump"

# ---- 3. Candidata --------------------------------------------------------------------------------------------------
paso "3. $(basename "$CANDIDATO") migra V$V_ANTERIOR -> V$V_CANDIDATO"
T0="$(date +%s)"
arrancar "$CANDIDATO" "$BASE" "$INFORME/3-candidato.log"
listo 300 || die "la candidata no arrancó sobre la base de la versión anterior (3-candidato.log)"
T_MIGRACION=$(($(date +%s) - T0))
detener
[ "$(version_de "$BASE")" = "$V_CANDIDATO" ] || die "la base no quedó en V$V_CANDIDATO"
conteos "$BASE" > "$INFORME/conteos-migrada.txt"
diff "$INFORME/conteos-antes.txt" "$INFORME/conteos-migrada.txt" || die "la migración cambió los conteos"
echo "OK  migró en ${T_MIGRACION} s (incluye el arranque) y los datos siguen"

# ---- 4. El binario anterior sobre el esquema migrado ---------------------------------------------------------------
paso "4. $(basename "$ANTERIOR") otra vez, sobre el esquema V$V_CANDIDATO"
arrancar "$ANTERIOR" "$BASE" "$INFORME/4-anterior-sobre-migrada.log"
if listo 240; then
    CLASIFICACION=ROLLBACK_BINARIO
    MOTIVO="el jar anterior arranca sobre el esquema migrado"
else
    CLASIFICACION=RESTAURAR_Y_CONCILIAR
    MOTIVO="$(grep -m 1 -oE 'Schema-validation: [^]]*|missing (column|table) [^ ]*|FlywayValidateException.*|Validate failed: .*' \
        "$INFORME/4-anterior-sobre-migrada.log" | cut -c1-300 || true)"
    MOTIVO="el jar anterior no arranca${MOTIVO:+: $MOTIVO}"
fi
detener
echo "$CLASIFICACION: $MOTIVO"

# ---- 5. Restauración del respaldo de antes de migrar ----------------------------------------------------------------
paso "5. restaurar el respaldo de antes de migrar y volver al jar anterior"
sql -c "CREATE DATABASE $BASE_R OWNER $ROL" > /dev/null
T0="$(date +%s)"
(cd / && runuser -u postgres -- pg_restore --no-owner --role="$ROL" -d "$BASE_R") < "$TRABAJO/antes.dump"
arrancar "$ANTERIOR" "$BASE_R" "$INFORME/5-anterior-restaurada.log"
listo 240 || die "el jar anterior no arrancó sobre el respaldo restaurado (5-anterior-restaurada.log)"
T_RESTAURACION=$(($(date +%s) - T0))
detener
conteos "$BASE_R" > "$INFORME/conteos-restaurada.txt"
diff "$INFORME/conteos-antes.txt" "$INFORME/conteos-restaurada.txt" || die "la restauración no devolvió los mismos datos"
[ "$(version_de "$BASE_R")" = "$V_ANTERIOR" ] || die "la base restaurada no está en V$V_ANTERIOR"
echo "OK  restaurado y el jar anterior arriba en ${T_RESTAURACION} s"

# ---- Informe -----------------------------------------------------------------------------------------------------
jq -n --arg anterior "$(basename "$ANTERIOR")" --arg candidato "$(basename "$CANDIDATO")" \
    --arg va "$V_ANTERIOR" --arg vc "$V_CANDIDATO" --arg clas "$CLASIFICACION" --arg motivo "$MOTIVO" \
    --arg datos "$DATOS" --argjson tm "$T_MIGRACION" --argjson tr "$T_RESTAURACION" \
    '{anterior: $anterior, candidato: $candidato, esquema_anterior: ("V" + $va), esquema_candidato: ("V" + $vc),
      clasificacion: $clas, motivo: $motivo, datos: $datos, segundos_migracion: $tm, segundos_restauracion: $tr,
      restauracion: "OK: el respaldo de antes de migrar vuelve con el jar anterior y los mismos conteos"}' \
    > "$INFORME/migracion-rollback.json"
cat "$INFORME/migracion-rollback.json"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "### Migración y vuelta atrás (IMP-09, C19)"
        echo
        echo "| | |"
        echo "|---|---|"
        echo "| De | \`$(basename "$ANTERIOR")\` (V$V_ANTERIOR) |"
        echo "| A | \`$(basename "$CANDIDATO")\` (V$V_CANDIDATO) |"
        echo "| Clasificación | **$CLASIFICACION** |"
        echo "| Motivo | $MOTIVO |"
        echo "| Migración | ${T_MIGRACION} s |"
        echo "| Restauración del respaldo previo | ${T_RESTAURACION} s, conteos iguales |"
        echo
        echo "Comparar con la tabla de docs/recuperacion.md#migraciones."
    } >> "$GITHUB_STEP_SUMMARY"
fi
paso "ensayo de migración completo"
