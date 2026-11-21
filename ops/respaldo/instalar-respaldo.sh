#!/usr/bin/env bash
# IMP-09 (C10). Respaldo del clúster PostgreSQL con pgBackRest (completo semanal, diferencial diario y WAL continuo:
# recuperación a un punto en el tiempo) y copia diaria de las imágenes de cada ambiente al bucket de respaldo.
#
#   sudo bash ops/respaldo/instalar-respaldo.sh [--reiniciar-postgresql]
#
# Idempotente, como provisionar.sh:
#   1. La primera vez copia respaldo.env.example a /etc/uvostore/respaldo.env (600 root:root), genera la clave de
#      cifrado del repositorio y se detiene (salida 2) hasta que no quede ningún CAMBIAR_.
#   2. Escribe /etc/pgbackrest/pgbackrest.conf y activa el archivado de WAL con ALTER SYSTEM.
#   3. archive_mode solo cambia reiniciando PostgreSQL, y reiniciar corta también a UvoPOS (comparten el clúster).
#      Por eso NO reinicia solo: avisa y se detiene (salida 3) hasta que operaciones lo haga en una ventana acordada,
#      o hasta que se le pase --reiniciar-postgresql (el ensayo de CI).
#   4. Crea la stanza, comprueba que el WAL llega al repositorio (pgbackrest check) y deja los timers de systemd:
#      completo (domingo 03:00), diferencial (lunes a sábado 03:00), imágenes (04:00) y métricas (cada 5 minutos).
#
# No hace el primer respaldo: es largo y conviene lanzarlo a mano (systemctl start uvostore-respaldo-completo) y
# mirarlo terminar. Hasta entonces la alerta UvoStoreRespaldoAtrasado sigue encendida, a propósito.
#
# Nunca imprime un secreto.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPS="$(cd "$DIR/.." && pwd)"
# shellcheck source-path=SCRIPTDIR/../servidor
. "$OPS/servidor/comun.sh"

REINICIAR=no
for arg in "$@"; do
    case "$arg" in
        --reiniciar-postgresql) REINICIAR=si ;;
        *) die "opción desconocida: $arg (uso: $0 [--reiniciar-postgresql])" ;;
    esac
done

requerir_root
requerir_comandos psql runuser systemctl openssl

ENV_RESPALDO="$UVOSTORE_ETC/respaldo.env"
CONF=/etc/pgbackrest/pgbackrest.conf
sql() { (cd / && runuser -u postgres -- psql -X -At -v ON_ERROR_STOP=1 -c "$1"); }

# ---- 1. Archivo de configuración ------------------------------------------------------------------------------
mkdir -p "$UVOSTORE_ETC"
if [ ! -f "$ENV_RESPALDO" ]; then
    info "creando $ENV_RESPALDO"
    install -m 600 -o root -g root /dev/null "$ENV_RESPALDO"
    sed "s|@@GENERAR@@|$(openssl rand -base64 48 | tr -d '\n=+/' | cut -c1-48)|" "$DIR/respaldo.env.example" > "$ENV_RESPALDO"
    chmod 600 "$ENV_RESPALDO"
    aviso "se generó RESPALDO_CIPHER_PASS en $ENV_RESPALDO: cópiala AHORA al gestor de contraseñas del equipo"
    aviso "(docs/recuperacion.md, «Custodia»). Sin ella, los respaldos no se pueden leer."
fi
v() { valor_env "$ENV_RESPALDO" "$1"; }
if grep -q '^[A-Z0-9_]*=CAMBIAR_' "$ENV_RESPALDO"; then
    aviso "faltan valores en $ENV_RESPALDO:"
    grep -o '^[A-Z0-9_]*=CAMBIAR_' "$ENV_RESPALDO" | cut -d= -f1 | sed 's/^/    /' >&2
    exit 2
fi

STANZA="$(v RESPALDO_STANZA)"
TIPO="$(v RESPALDO_REPO_TIPO)"
CIPHER="$(v RESPALDO_CIPHER_PASS)"
RETENCION="$(v RESPALDO_RETENCION_COMPLETOS)"
METRICAS_DIR="$(v RESPALDO_METRICAS_DIR)"
[ -n "$STANZA" ] || die "RESPALDO_STANZA vacío"
[ "${#CIPHER}" -ge 32 ] || die "RESPALDO_CIPHER_PASS tiene que tener al menos 32 caracteres"
case "$RETENCION" in '' | *[!0-9]*) die "RESPALDO_RETENCION_COMPLETOS tiene que ser un número" ;; esac

# ---- 2. pgBackRest ----------------------------------------------------------------------------------------------
if ! command -v pgbackrest > /dev/null 2>&1; then
    info "instalando pgbackrest"
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends pgbackrest > /dev/null
fi
PG_DATA="$(sql 'SHOW data_directory')"
PG_PORT="$(sql 'SHOW port')"

repo="$(mktemp)"
case "$TIPO" in
    s3)
        region="$(v RESPALDO_S3_REGION)"
        endpoint="$(v RESPALDO_S3_ENDPOINT)"
        {
            echo "repo1-type=s3"
            echo "repo1-path=/pgbackrest"
            echo "repo1-s3-bucket=$(v RESPALDO_S3_BUCKET)"
            echo "repo1-s3-region=$region"
            echo "repo1-s3-endpoint=${endpoint:-s3.$region.amazonaws.com}"
            echo "repo1-s3-key=$(v RESPALDO_S3_KEY)"
            echo "repo1-s3-key-secret=$(v RESPALDO_S3_KEY_SECRET)"
            echo "repo1-s3-uri-style=$(v RESPALDO_S3_URI_STYLE)"
            echo "repo1-storage-port=$(v RESPALDO_S3_PORT)"
            echo "repo1-storage-verify-tls=$(v RESPALDO_S3_VERIFY_TLS)"
        } > "$repo"
        ;;
    posix)
        aviso "RESPALDO_REPO_TIPO=posix: el repositorio queda en este mismo disco. Solo para ensayar."
        install -d -m 750 -o postgres -g postgres /var/lib/pgbackrest
        printf 'repo1-type=posix\nrepo1-path=/var/lib/pgbackrest\n' > "$repo"
        ;;
    *) die "RESPALDO_REPO_TIPO tiene que ser s3 o posix (se recibió '$TIPO')" ;;
esac

install -d -m 750 -o postgres -g postgres /etc/pgbackrest /var/log/pgbackrest /var/spool/pgbackrest
plantilla="$(mktemp)"
# El bloque del repositorio tiene varias líneas: va con awk, antes de renderizar el resto.
awk -v f="$repo" '$0 == "@@REPO@@" { while ((getline l < f) > 0) print l; next } { print }' \
    "$DIR/pgbackrest.conf.template" > "$plantilla"
nuevo="$(mktemp)"
renderizar "$plantilla" "$nuevo" CIPHER_PASS="$CIPHER" RETENCION="$RETENCION" STANZA="$STANZA" \
    PG_DATA="$PG_DATA" PG_PORT="$PG_PORT"
rm -f "$repo" "$plantilla"
if [ -f "$CONF" ] && cmp -s "$nuevo" "$CONF"; then
    rm -f "$nuevo"
else
    info "escribiendo $CONF"
    install -m 640 -o postgres -g postgres "$nuevo" "$CONF"
    rm -f "$nuevo"
fi

# ---- 3. Archivado de WAL ---------------------------------------------------------------------------------------
archive_command="pgbackrest --stanza=$STANZA archive-push %p"
cambiado=no
fijar_pg() { # fijar_pg <parámetro> <valor> : en postgresql.auto.conf, solo si no está ya así
    local actual
    actual="$(sql "SELECT setting FROM pg_file_settings WHERE name = '$1' AND sourcefile LIKE '%postgresql.auto.conf' ORDER BY seqno DESC LIMIT 1")"
    if [ "$actual" != "$2" ]; then
        sql "ALTER SYSTEM SET $1 = '$2'" > /dev/null
        cambiado=si
    fi
}
fijar_pg archive_mode on
fijar_pg archive_command "$archive_command"
# Fuerza un segmento de WAL nuevo cada minuto si hubo escrituras: es lo que acota el RPO.
fijar_pg archive_timeout 60
fijar_pg wal_level replica
[ "$cambiado" = si ] && sql 'SELECT pg_reload_conf()' > /dev/null

if [ "$(sql 'SHOW archive_mode')" != on ] || [ "$(sql 'SHOW wal_level')" != replica ]; then
    if [ "$REINICIAR" = si ]; then
        info "reiniciando PostgreSQL para activar archive_mode (--reiniciar-postgresql)"
        systemctl restart postgresql
        for _ in $(seq 1 60); do sql 'SELECT 1' > /dev/null 2>&1 && break; sleep 1; done
    else
        aviso "archive_mode queda activo recién al reiniciar PostgreSQL, y eso corta también a UvoPOS."
        aviso "Coordinarlo con el equipo POS y, en la ventana acordada:"
        aviso "    sudo systemctl restart postgresql && sudo bash $0"
        exit 3
    fi
fi
[ "$(sql 'SHOW archive_mode')" = on ] || die "archive_mode sigue apagado después de reiniciar"

# ---- 4. Stanza y comprobación ----------------------------------------------------------------------------------
pgb() { (cd / && runuser -u postgres -- pgbackrest --stanza="$STANZA" "$@"); }
info "stanza $STANZA"
pgb stanza-create > /dev/null
# check fuerza un cambio de segmento y espera a que llegue al repositorio: si el WAL no se archiva, falla aquí y
# no a las 03:00 del domingo.
pgb check
info "pgbackrest check OK: el WAL llega al repositorio"

# ---- 5. Timers --------------------------------------------------------------------------------------------------
install -m 755 "$DIR/metricas-respaldo.sh" /usr/local/sbin/uvostore-metricas-respaldo
install -m 755 "$DIR/respaldar-imagenes.sh" /usr/local/sbin/uvostore-respaldar-imagenes
install -d -m 755 "$METRICAS_DIR"

unidad() { # unidad <nombre> <descripción> <ExecStart> <OnCalendar>
    local servicio="/etc/systemd/system/$1.service" timer="/etc/systemd/system/$1.timer" tmp post=""
    tmp="$(mktemp)"
    # Después de cada respaldo, las métricas al día (la unidad de métricas no se llama a sí misma).
    [ "$1" = uvostore-respaldo-metricas ] || post="ExecStartPost=-/usr/local/sbin/uvostore-metricas-respaldo"
    cat > "$tmp" << EOF
[Unit]
Description=$2
After=postgresql.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=$3
$post
Nice=10
IOSchedulingClass=idle
EOF
    cmp -s "$tmp" "$servicio" 2> /dev/null || install -m 644 "$tmp" "$servicio"
    cat > "$tmp" << EOF
[Unit]
Description=$2 (programado)

[Timer]
OnCalendar=$4
Persistent=true
RandomizedDelaySec=300

[Install]
WantedBy=timers.target
EOF
    cmp -s "$tmp" "$timer" 2> /dev/null || install -m 644 "$tmp" "$timer"
    rm -f "$tmp"
}
unidad uvostore-respaldo-completo "Respaldo completo del clúster (pgBackRest)" \
    "/usr/sbin/runuser -u postgres -- pgbackrest --stanza=$STANZA --type=full backup" "Sun *-*-* 03:00:00"
unidad uvostore-respaldo-diferencial "Respaldo diferencial del clúster (pgBackRest)" \
    "/usr/sbin/runuser -u postgres -- pgbackrest --stanza=$STANZA --type=diff backup" "Mon..Sat *-*-* 03:00:00"
unidad uvostore-respaldo-imagenes "Copia de las imágenes al bucket de respaldo" \
    "/usr/local/sbin/uvostore-respaldar-imagenes" "*-*-* 04:00:00"
# Las métricas también solas, para que el atraso del WAL se vea entre respaldos.
unidad uvostore-respaldo-metricas "Métricas del respaldo para Prometheus" \
    "/usr/local/sbin/uvostore-metricas-respaldo" "*:0/5"
systemctl daemon-reload
for t in completo diferencial imagenes metricas; do
    systemctl enable --now "uvostore-respaldo-$t.timer" > /dev/null 2>&1
done
/usr/local/sbin/uvostore-metricas-respaldo || true

info "respaldo instalado. Primer respaldo completo, mirándolo terminar:"
info "    sudo systemctl start uvostore-respaldo-completo && sudo -u postgres pgbackrest --stanza=$STANZA info"
