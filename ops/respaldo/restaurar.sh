#!/usr/bin/env bash
# IMP-09 (C10, §11.2). Restauración AISLADA: el clúster restaurado en otra carpeta y otro puerto, y una instancia de
# UvoStore encima en modo recuperación (APP_RECOVERY_MODE=true). Nunca toca el clúster vivo ni la instancia del
# ambiente: es para comprobar el respaldo, medir RPO/RTO y conciliar antes de decidir qué se hace con lo restaurado.
#
#   sudo bash ops/respaldo/restaurar.sh <ambiente> (--hasta '<AAAA-MM-DD HH:MM:SS±ZZ>' | --ultimo) [opciones]
#   sudo bash ops/respaldo/restaurar.sh <ambiente> --detener
#
# Opciones:
#   --directorio <dir>      dónde queda todo (por omisión /var/lib/uvostore-restauracion/<ambiente>)
#   --puerto-pg <n>         puerto del clúster restaurado (5433)
#   --puerto-app <n>        puerto de la instancia restaurada (8091 staging, 8092 production)
#   --jar <ruta>            el jar que se levanta (por omisión current.jar del ambiente; ver «Versión» abajo)
#   --permitir-migrar       dejar que el jar aplique migraciones que la base restaurada no tiene
#   --bucket-aislado <b>    copia las imágenes del respaldo a ese bucket (tiene que existir) y la instancia lo usa
#   --smtp <host:puerto>    a dónde mandar correo (127.0.0.1:1025 = Mailpit). El modo recuperación no envía nada,
#                           pero si algo escapara, cae en un buzón de prueba y no en un comprador
#   --llave-stdin           lee APP_ENCRYPTION_KEY de la entrada estándar (la copia del gestor de contraseñas) en
#                           vez de usar la del archivo de entorno del servidor; nunca va como argumento ni al log
#   --referencia '<fecha>'  el momento del incidente, para medir el RPO (por omisión, --hasta o el inicio)
#   --forzar                reemplazar una restauración anterior en el mismo directorio
#
# Versión: la instancia restaurada no puede tener un esquema más nuevo que el jar (Flyway no baja), y un jar más nuevo
# migraría la copia hacia adelante, que ya no sería lo que había en el respaldo. Si el jar trae migraciones que la base
# restaurada no tiene, se detiene y pide el jar de esa época (releases/) o --permitir-migrar.
#
# Mide y deja en <directorio>/acta-restauracion.md: inicio, clúster listo, aplicación lista, duración (RTO técnico),
# última transacción restaurada y pérdida respecto de la referencia (RPO). La conciliación y la decisión de volver a
# admitir tráfico van en el acta completa de docs/recuperacion.md.
#
# El clúster es compartido con UvoPOS: lo restaurado trae también su base, al mismo punto. Aquí no se usa.

set -euo pipefail

DIR_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPS="$(cd "$DIR_SCRIPTS/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/../servidor
. "$OPS/servidor/comun.sh"

[ $# -ge 2 ] || die "uso: $0 <ambiente> (--hasta '<fecha>' | --ultimo | --detener) [opciones]"
AMBIENTE="$1"
shift
validar_ambiente "$AMBIENTE"
requerir_root

HASTA="" ULTIMO=no DETENER=no JAR="" PERMITIR_MIGRAR=no BUCKET_AISLADO="" SMTP="127.0.0.1:1025"
LLAVE_STDIN=no REFERENCIA="" FORZAR=no
DIRECTORIO="/var/lib/uvostore-restauracion/$AMBIENTE"
PUERTO_PG=5433
case "$AMBIENTE" in staging) PUERTO_APP=8091 ;; production) PUERTO_APP=8092 ;; esac
while [ $# -gt 0 ]; do
    case "$1" in
        --hasta) HASTA="$2"; shift 2 ;;
        --ultimo) ULTIMO=si; shift ;;
        --detener) DETENER=si; shift ;;
        --directorio) DIRECTORIO="$2"; shift 2 ;;
        --puerto-pg) PUERTO_PG="$2"; shift 2 ;;
        --puerto-app) PUERTO_APP="$2"; shift 2 ;;
        --jar) JAR="$(readlink -f "$2")"; shift 2 ;;
        --permitir-migrar) PERMITIR_MIGRAR=si; shift ;;
        --bucket-aislado) BUCKET_AISLADO="$2"; shift 2 ;;
        --smtp) SMTP="$2"; shift 2 ;;
        --llave-stdin) LLAVE_STDIN=si; shift ;;
        --referencia) REFERENCIA="$2"; shift 2 ;;
        --forzar) FORZAR=si; shift ;;
        *) die "opción desconocida: $1" ;;
    esac
done

UNIDAD="uvostore-$AMBIENTE-restauracion"
USUARIO="uvostore-$AMBIENTE"
PGDATA_R="$DIRECTORIO/pgdata"
SOCKET_R="$DIRECTORIO/run"
ENV_R="$DIRECTORIO/uvostore.env"
ACTA="$DIRECTORIO/acta-restauracion.md"
ENV_APP="$(archivo_env "$AMBIENTE")"
ENV_RESPALDO="$UVOSTORE_ETC/respaldo.env"
sql_vivo() { (cd / && runuser -u postgres -- psql -X -At -v ON_ERROR_STOP=1 -c "$1"); }
PG_MAYOR="$(sql_vivo "SELECT current_setting('server_version_num')::int / 10000")"
PGBIN="/usr/lib/postgresql/$PG_MAYOR/bin"
pg_r() { (cd / && runuser -u postgres -- "$PGBIN/$1" "${@:2}"); }
sql_r() { (cd / && runuser -u postgres -- psql -X -At -h "$SOCKET_R" -p "$PUERTO_PG" -d "$1" -v ON_ERROR_STOP=1 -c "$2"); }

detener() {
    systemctl stop "$UNIDAD" > /dev/null 2>&1 || true
    systemctl reset-failed "$UNIDAD" > /dev/null 2>&1 || true
    if [ -f "$PGDATA_R/postmaster.pid" ]; then
        pg_r pg_ctl -D "$PGDATA_R" -m fast -w stop > /dev/null || true
    fi
}

if [ "$DETENER" = si ]; then
    detener
    info "restauración de $AMBIENTE detenida; los datos siguen en $DIRECTORIO (borrarlos es una decisión aparte)"
    exit 0
fi

[ -n "$HASTA" ] || [ "$ULTIMO" = si ] || die "falta --hasta '<fecha>' o --ultimo"
[ -z "$HASTA" ] || [ "$ULTIMO" = no ] || die "--hasta y --ultimo no van juntos"
[ -f "$ENV_RESPALDO" ] || die "falta $ENV_RESPALDO: el respaldo no está instalado (instalar-respaldo.sh)"
[ -f "$ENV_APP" ] || die "falta $ENV_APP"
requerir_comandos pgbackrest jq curl systemd-run unzip
STANZA="$(valor_env "$ENV_RESPALDO" RESPALDO_STANZA)"

# ---- Salvaguardas: nunca sobre lo vivo ---------------------------------------------------------------------------
PGDATA_VIVO="$(sql_vivo 'SHOW data_directory')"
PUERTO_VIVO="$(sql_vivo 'SHOW port')"
case "$(readlink -m "$PGDATA_R")/" in
    "$(readlink -m "$PGDATA_VIVO")/"*) die "el directorio de restauración cae dentro del clúster vivo ($PGDATA_VIVO)" ;;
esac
[ "$PUERTO_PG" != "$PUERTO_VIVO" ] || die "el puerto $PUERTO_PG es el del clúster vivo"
[ "$PUERTO_APP" != "$(valor_env "$ENV_APP" SERVER_PORT)" ] || die "el puerto $PUERTO_APP es el de la instancia de $AMBIENTE"
if [ -d "$PGDATA_R" ]; then
    [ "$FORZAR" = si ] || die "ya hay una restauración en $DIRECTORIO (--forzar para reemplazarla, o --directorio)"
    detener
    rm -rf "$PGDATA_R"
fi
# 755: postgres tiene que llegar a pgdata y run, y el usuario del ambiente a app. Lo sensible va con sus propios
# permisos: pgdata y run 700 postgres, el entorno 640 root:<usuario>, el acta y el informe 640 root.
install -d -m 755 -o root -g root "$DIRECTORIO"
install -d -m 700 -o postgres -g postgres "$PGDATA_R" "$SOCKET_R"

# La llave, antes de empezar a medir: es lo que se trae del gestor de contraseñas.
LLAVE=""
if [ "$LLAVE_STDIN" = si ]; then
    if [ -t 0 ]; then
        read -r -s -p "APP_ENCRYPTION_KEY de $AMBIENTE (del gestor de contraseñas): " LLAVE
        echo
    else
        read -r LLAVE
    fi
    [ -n "$LLAVE" ] || die "no se recibió la llave por la entrada estándar"
fi

T0="$(date +%s)"
INICIO="$(date -Is)"

# ---- 1. Clúster ----------------------------------------------------------------------------------------------------
if [ "$ULTIMO" = si ]; then
    info "restaurando $STANZA hasta el último WAL archivado"
    destino_pitr=(--type=default)
else
    info "restaurando $STANZA hasta $HASTA"
    destino_pitr=(--type=time "--target=$HASTA" --target-action=promote)
fi
# --archive-mode=off: el clúster restaurado NO archiva. Si archivara, mandaría su WAL (otra línea de tiempo) al mismo
# repositorio que el clúster vivo.
(cd / && runuser -u postgres -- pgbackrest --stanza="$STANZA" --pg1-path="$PGDATA_R" --archive-mode=off \
    "${destino_pitr[@]}" --log-level-console=warn restore)

# En Ubuntu, postgresql.conf y pg_hba.conf viven en /etc/postgresql, fuera del directorio de datos: el respaldo no
# los trae. Lo mínimo para levantar en local, solo con el socket propio y 127.0.0.1.
# La recuperación de archivo (con hot_standby, el valor por omisión) no arranca si estos parámetros quedan por debajo
# de los del clúster que generó el WAL: se copian del vivo, que es ese mismo clúster.
cat > "$PGDATA_R/postgresql.conf" << EOF
# restaurar.sh (IMP-09): clúster restaurado y aislado. No archiva y no escucha fuera de 127.0.0.1.
port = $PUERTO_PG
listen_addresses = '127.0.0.1'
unix_socket_directories = '$SOCKET_R'
hba_file = '$PGDATA_R/pg_hba.conf'
ident_file = '$PGDATA_R/pg_ident.conf'
max_connections = $(sql_vivo 'SHOW max_connections')
max_worker_processes = $(sql_vivo 'SHOW max_worker_processes')
max_wal_senders = $(sql_vivo 'SHOW max_wal_senders')
max_prepared_transactions = $(sql_vivo 'SHOW max_prepared_transactions')
max_locks_per_transaction = $(sql_vivo 'SHOW max_locks_per_transaction')
shared_buffers = 128MB
archive_mode = off
EOF
cat > "$PGDATA_R/pg_hba.conf" << 'EOF'
local   all   postgres                 peer
host    all   all        127.0.0.1/32  scram-sha-256
EOF
: > "$PGDATA_R/pg_ident.conf"
# ALTER SYSTEM del clúster vivo viene en postgresql.auto.conf y gana sobre postgresql.conf: fuera el archivado.
sed -i -e '/^archive_command/d' -e '/^archive_mode/d' "$PGDATA_R/postgresql.auto.conf"
echo "archive_mode = 'off'" >> "$PGDATA_R/postgresql.auto.conf"
chown postgres:postgres "$PGDATA_R"/*.conf

info "levantando el clúster restaurado en 127.0.0.1:$PUERTO_PG"
if ! pg_r pg_ctl -D "$PGDATA_R" -l "$SOCKET_R/postgresql.log" -w -t 7200 start > /dev/null; then
    tail -n 40 "$SOCKET_R/postgresql.log" >&2 || true
    die "el clúster restaurado no arrancó (log completo en $SOCKET_R/postgresql.log)"
fi
for _ in $(seq 1 7200); do
    [ "$(sql_r postgres 'SELECT pg_is_in_recovery()' 2> /dev/null || echo t)" = f ] && break
    sleep 1
done
[ "$(sql_r postgres 'SELECT pg_is_in_recovery()')" = f ] || die "el clúster restaurado no terminó la recuperación"
T_PG="$(date +%s)"
ULTIMA_TX="$(sql_r postgres "SELECT COALESCE(to_char(pg_last_xact_replay_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), '')")"
BASE="uvostore_$AMBIENTE"
FLYWAY_R="$(sql_r "$BASE" "SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'")"
info "clúster listo en $((T_PG - T0)) s; última transacción restaurada: ${ULTIMA_TX:-desconocida}; esquema V$FLYWAY_R"

# ---- 2. Versión de la aplicación -----------------------------------------------------------------------------------
JAR="${JAR:-$(readlink -f "${UVOSTORE_OPT_PREFIX}$AMBIENTE/current.jar")}"
[ -f "$JAR" ] || die "no existe el jar $JAR"
FLYWAY_JAR="$( (unzip -Z1 "$JAR" 2> /dev/null || true) | sed -n 's|.*db/migration/V\([0-9]*\)__.*|\1|p' | sort -n | tail -n 1)"
if [ -n "$FLYWAY_JAR" ] && [ "$FLYWAY_JAR" -gt "$FLYWAY_R" ] && [ "$PERMITIR_MIGRAR" = no ]; then
    die "$(basename "$JAR") trae migraciones hasta V$FLYWAY_JAR y la base restaurada está en V$FLYWAY_R: levantarlo la
migraría hacia adelante. Usar el jar de esa época (--jar ${UVOSTORE_OPT_PREFIX}$AMBIENTE/releases/<versión>.jar) o
--permitir-migrar si eso es lo que se quiere ensayar. Clúster restaurado arriba; --detener para bajarlo."
fi
if [ -n "$FLYWAY_JAR" ] && [ "$FLYWAY_JAR" -lt "$FLYWAY_R" ]; then
    die "$(basename "$JAR") llega a V$FLYWAY_JAR y la base restaurada está en V$FLYWAY_R: no arranca (Flyway no baja).
Usar un jar más nuevo con --jar."
fi

# ---- 3. Imágenes ---------------------------------------------------------------------------------------------------
if [ -n "$BUCKET_AISLADO" ]; then
    requerir_comandos aws
    endpoint="$(valor_env "$ENV_RESPALDO" RESPALDO_AWS_ENDPOINT_URL)"
    info "imágenes: s3://$(valor_env "$ENV_RESPALDO" RESPALDO_S3_BUCKET)/imagenes/$AMBIENTE/ -> s3://$BUCKET_AISLADO"
    AWS_ACCESS_KEY_ID="$(valor_env "$ENV_RESPALDO" RESPALDO_S3_KEY)" \
        AWS_SECRET_ACCESS_KEY="$(valor_env "$ENV_RESPALDO" RESPALDO_S3_KEY_SECRET)" \
        AWS_DEFAULT_REGION="$(valor_env "$ENV_RESPALDO" RESPALDO_S3_REGION)" \
        aws ${endpoint:+--endpoint-url "$endpoint"} s3 sync --only-show-errors \
        "s3://$(valor_env "$ENV_RESPALDO" RESPALDO_S3_BUCKET)/imagenes/$AMBIENTE/" "s3://$BUCKET_AISLADO"
fi
T_IMG="$(date +%s)"

# ---- 4. Entorno de la instancia aislada ----------------------------------------------------------------------------
install -m 640 -o root -g "$USUARIO" "$ENV_APP" "$ENV_R"
fijar_env "$ENV_R" APP_RECOVERY_MODE true
fijar_env "$ENV_R" SERVER_ADDRESS 127.0.0.1
fijar_env "$ENV_R" SERVER_PORT "$PUERTO_APP"
fijar_env "$ENV_R" DB_URL "jdbc:postgresql://127.0.0.1:$PUERTO_PG/$BASE"
fijar_env "$ENV_R" POS_ENABLED false
fijar_env "$ENV_R" MAIL_HOST "${SMTP%:*}"
fijar_env "$ENV_R" MAIL_PORT "${SMTP##*:}"
fijar_env "$ENV_R" MAIL_USERNAME ""
fijar_env "$ENV_R" MAIL_PASSWORD ""
fijar_env "$ENV_R" MAIL_SMTP_AUTH false
fijar_env "$ENV_R" MAIL_SMTP_STARTTLS false
# Sus errores no se mezclan con los del ambiente en Sentry.
fijar_env "$ENV_R" SENTRY_DSN ""
fijar_env "$ENV_R" SENTRY_ENVIRONMENT "$AMBIENTE-restauracion"
[ -z "$BUCKET_AISLADO" ] || fijar_env "$ENV_R" APP_STORAGE_S3_BUCKET "$BUCKET_AISLADO"
[ -z "$LLAVE" ] || fijar_env "$ENV_R" APP_ENCRYPTION_KEY "$LLAVE"
LLAVE=""

# ---- 5. Instancia ------------------------------------------------------------------------------------------------
JAVA="$(readlink -f "$(command -v java)")"
install -d -m 750 -o "$USUARIO" -g "$USUARIO" "$DIRECTORIO/app"
systemctl reset-failed "$UNIDAD" > /dev/null 2>&1 || true
info "levantando $(basename "$JAR") en modo recuperación en 127.0.0.1:$PUERTO_APP (unidad $UNIDAD)"
systemd-run --unit="$UNIDAD" --description="UvoStore $AMBIENTE restaurado (IMP-09, modo recuperación)" \
    --property=User="$USUARIO" --property=Group="$USUARIO" --property=EnvironmentFile="$ENV_R" \
    --property=WorkingDirectory="$DIRECTORIO/app" --property=KillSignal=SIGTERM --property=TimeoutStopSec=60 \
    "$JAVA" -Xms256m -Xmx768m -XX:+ExitOnOutOfMemoryError -jar "$JAR" > /dev/null
esperar_readiness "$PUERTO_APP" 300 "$UNIDAD" || die "la instancia restaurada no quedó lista (journalctl -u $UNIDAD)"
T_APP="$(date +%s)"
journalctl -u "$UNIDAD" --no-pager | grep -q 'Modo recuperación ACTIVO' \
    || die "la instancia no informa el modo recuperación en su log: revisar antes de seguir"

# La sonda de descifrado (CredentialDecryptProbe) dejó su resultado en el log.
SONDA="$(journalctl -u "$UNIDAD" --no-pager -o cat | grep -o 'APP_ENCRYPTION_KEY no abre [0-9]* valores\|APP_ENCRYPTION_KEY abre todos los valores cifrados' | tail -n 1)"

# ---- 6. Acta -------------------------------------------------------------------------------------------------------
REF="${REFERENCIA:-${HASTA:-$INICIO}}"
RPO="desconocido"
if [ -n "$ULTIMA_TX" ]; then
    RPO="$(( $(date -d "$REF" +%s) - $(date -d "$ULTIMA_TX" +%s) )) s"
fi
cat > "$ACTA" << EOF
# Acta técnica de restauración — $AMBIENTE

Generada por ops/respaldo/restaurar.sh. Completar con la conciliación y la decisión (docs/recuperacion.md).

| Campo | Valor |
|---|---|
| Ambiente | $AMBIENTE |
| Stanza | $STANZA |
| Punto pedido | ${HASTA:-último WAL archivado} |
| Referencia del incidente (RPO) | $REF |
| Inicio | $INICIO |
| Clúster listo | $(date -d "@$T_PG" -Is) ($((T_PG - T0)) s) |
| Imágenes copiadas | ${BUCKET_AISLADO:-no se pidió} ($((T_IMG - T_PG)) s) |
| Aplicación lista | $(date -d "@$T_APP" -Is) ($((T_APP - T_IMG)) s) |
| **Duración técnica (RTO sin conciliación)** | **$((T_APP - T0)) s** |
| Última transacción restaurada | ${ULTIMA_TX:-desconocida} |
| **Pérdida respecto de la referencia (RPO)** | **$RPO** |
| Esquema restaurado | V$FLYWAY_R |
| Jar | $(basename "$JAR") (migraciones hasta V${FLYWAY_JAR:-?}) |
| Llave | $([ "$LLAVE_STDIN" = si ] && echo "entrada estándar (gestor de contraseñas)" || echo "archivo de entorno del servidor") |
| Sonda de descifrado | ${SONDA:-sin resultado en el log} |
| Instancia | unidad $UNIDAD, 127.0.0.1:$PUERTO_APP, APP_RECOVERY_MODE=true |
| Clúster restaurado | $PGDATA_R, 127.0.0.1:$PUERTO_PG (archive_mode=off) |
EOF
chmod 640 "$ACTA"
cat "$ACTA"
echo
info "siguiente: bash $DIR_SCRIPTS/verificar-restauracion.sh $AMBIENTE --desde '<fecha>' (informe de conciliación)"
info "al terminar: bash $0 $AMBIENTE --detener"
