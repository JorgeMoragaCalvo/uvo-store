#!/usr/bin/env bash
# IMP-09. Métricas del respaldo para Prometheus, en el formato del colector de archivos de texto de node_exporter
# (--collector.textfile.directory=$RESPALDO_METRICAS_DIR). Lo corre el timer uvostore-respaldo-metricas cada
# 5 minutos y después de cada respaldo. Las alertas están en ops/monitoring/prometheus/alerts.yml (grupo
# uvostore-respaldo).
#
# Instalado como /usr/local/sbin/uvostore-metricas-respaldo; no depende de nada del repositorio.

set -euo pipefail

ENV_RESPALDO="${UVOSTORE_ETC:-/etc/uvostore}/respaldo.env"
v() { sed -n "s/^$1=//p" "$ENV_RESPALDO" | tail -n 1; }
STANZA="$(v RESPALDO_STANZA)"
DESTINO="$(v RESPALDO_METRICAS_DIR)"
DESTINO="${DESTINO:-/var/lib/prometheus/node-exporter}"
mkdir -p "$DESTINO"

sql() { (cd / && runuser -u postgres -- psql -X -At -c "$1"); }
tmp="$(mktemp "$DESTINO/.uvostore_respaldo.XXXXXX")"
{
    echo "# HELP uvostore_backup_last_success_timestamp_seconds Fin del último respaldo terminado, por tipo (pgBackRest)."
    echo "# TYPE uvostore_backup_last_success_timestamp_seconds gauge"
    echo "# HELP uvostore_backup_repo_ok 1 si pgbackrest info lee el repositorio sin errores."
    echo "# TYPE uvostore_backup_repo_ok gauge"
    if info="$(cd / && runuser -u postgres -- pgbackrest --stanza="$STANZA" --output=json info 2> /dev/null)" \
        && [ "$(jq -r '.[0].status.code' <<< "$info")" = 0 ]; then
        echo "uvostore_backup_repo_ok 1"
        jq -r '.[0].backup // [] | group_by(.type)[] | max_by(.timestamp.stop)
               | "uvostore_backup_last_success_timestamp_seconds{type=\"\(.type)\"} \(.timestamp.stop)"' <<< "$info"
    else
        echo "uvostore_backup_repo_ok 0"
    fi

    # El archivado de WAL: si falla, cada minuto que pasa es un minuto que un desastre se lleva.
    estado="$(sql "SELECT COALESCE(EXTRACT(EPOCH FROM last_archived_time)::bigint, 0) || ' ' ||
                          CASE WHEN last_failed_time IS NOT NULL
                                AND (last_archived_time IS NULL OR last_failed_time > last_archived_time)
                               THEN 1 ELSE 0 END
                   FROM pg_stat_archiver" 2> /dev/null || echo "0 1")"
    echo "# HELP uvostore_wal_last_archived_timestamp_seconds Último segmento de WAL archivado."
    echo "# TYPE uvostore_wal_last_archived_timestamp_seconds gauge"
    echo "uvostore_wal_last_archived_timestamp_seconds ${estado% *}"
    echo "# HELP uvostore_wal_archive_failing 1 si el último intento de archivar WAL falló."
    echo "# TYPE uvostore_wal_archive_failing gauge"
    echo "uvostore_wal_archive_failing ${estado#* }"
    # Segmentos esperando archivarse: crece si el archivado se atrasa o falla (y llena el disco de pg_wal).
    pendientes="$(sql "SELECT count(*) FROM pg_ls_dir('pg_wal/archive_status') AS f WHERE f LIKE '%.ready'" 2> /dev/null || echo -1)"
    echo "# HELP uvostore_wal_ready_segments Segmentos de WAL esperando archivarse (-1 = no se pudo leer)."
    echo "# TYPE uvostore_wal_ready_segments gauge"
    echo "uvostore_wal_ready_segments $pendientes"
} > "$tmp"
chmod 644 "$tmp"
mv -f "$tmp" "$DESTINO/uvostore_respaldo.prom"
