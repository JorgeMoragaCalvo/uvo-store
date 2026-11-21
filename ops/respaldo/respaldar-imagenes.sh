#!/usr/bin/env bash
# IMP-09 (C10). Copia las imágenes de cada ambiente (RESPALDO_AMBIENTES) al bucket de respaldo, bajo
# imagenes/<ambiente>/. Lo corre el timer uvostore-respaldo-imagenes cada día; también a mano:
#
#   sudo /usr/local/sbin/uvostore-respaldar-imagenes          (instalado por instalar-respaldo.sh)
#   sudo bash ops/respaldo/respaldar-imagenes.sh              (desde el repositorio)
#
# Sin --delete: una imagen borrada en la tienda sigue en el respaldo. El bucket de respaldo tiene versionado y una
# política que le niega borrar a todo el que no sea operaciones (ops/s3/politica-bucket-respaldo.json), así que ni
# un error de este script ni unas credenciales robadas pueden vaciarlo. Las credenciales son las del respaldo
# (RESPALDO_S3_KEY), que leen el bucket de imágenes y escriben en el de respaldo; las de la aplicación no sirven
# para esto, y es a propósito (ops/s3/politica-iam-aplicacion.json).
#
# No depende de nada del repositorio: se instala solo.

set -euo pipefail

ETC="${UVOSTORE_ETC:-/etc/uvostore}"
ENV_RESPALDO="$ETC/respaldo.env"
v() { sed -n "s/^$2=//p" "$1" | tail -n 1; }
[ -f "$ENV_RESPALDO" ] || { echo "ERROR: falta $ENV_RESPALDO (instalar-respaldo.sh)" >&2; exit 1; }
command -v aws > /dev/null 2>&1 || { echo "ERROR: falta el CLI de AWS (aws)" >&2; exit 1; }

DESTINO="$(v "$ENV_RESPALDO" RESPALDO_S3_BUCKET)"
METRICAS_DIR="$(v "$ENV_RESPALDO" RESPALDO_METRICAS_DIR)"
METRICAS_DIR="${METRICAS_DIR:-/var/lib/prometheus/node-exporter}"
ENDPOINT="$(v "$ENV_RESPALDO" RESPALDO_AWS_ENDPOINT_URL)"
export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_DEFAULT_REGION
AWS_ACCESS_KEY_ID="$(v "$ENV_RESPALDO" RESPALDO_S3_KEY)"
AWS_SECRET_ACCESS_KEY="$(v "$ENV_RESPALDO" RESPALDO_S3_KEY_SECRET)"
AWS_DEFAULT_REGION="$(v "$ENV_RESPALDO" RESPALDO_S3_REGION)"
aws_s3() { aws ${ENDPOINT:+--endpoint-url "$ENDPOINT"} s3 "$@"; }

fallas=0
lineas=()
for ambiente in $(v "$ENV_RESPALDO" RESPALDO_AMBIENTES); do
    env_app="$ETC/$ambiente.env"
    if [ ! -f "$env_app" ]; then
        echo "==> $ambiente: sin $env_app, se omite"
        continue
    fi
    origen="$(v "$env_app" APP_STORAGE_S3_BUCKET)"
    if [ -z "$origen" ] || [ "$(v "$env_app" APP_STORAGE_DRIVER)" = local ]; then
        echo "==> $ambiente: no guarda imágenes en S3, se omite"
        continue
    fi
    echo "==> $ambiente: s3://$origen -> s3://$DESTINO/imagenes/$ambiente/"
    if aws_s3 sync --only-show-errors "s3://$origen" "s3://$DESTINO/imagenes/$ambiente/"; then
        lineas+=("uvostore_images_backup_last_success_timestamp_seconds{environment=\"$ambiente\"} $(date +%s)")
    else
        echo "ERROR: falló la copia de $ambiente" >&2
        fallas=$((fallas + 1))
    fi
done

# Métricas: se conserva la marca anterior de un ambiente que falló, para que la alerta vea su antigüedad real.
mkdir -p "$METRICAS_DIR"
prom="$METRICAS_DIR/uvostore_respaldo_imagenes.prom"
tmp="$(mktemp "$METRICAS_DIR/.uvostore_respaldo_imagenes.XXXXXX")"
{
    echo "# HELP uvostore_images_backup_last_success_timestamp_seconds Última copia completa de las imágenes al bucket de respaldo."
    echo "# TYPE uvostore_images_backup_last_success_timestamp_seconds gauge"
    for l in "${lineas[@]}"; do echo "$l"; done
    if [ -f "$prom" ]; then
        grep '^uvostore_images_backup_last_success_timestamp_seconds' "$prom" | while read -r previa; do
            amb="$(sed 's/.*environment="\([^"]*\)".*/\1/' <<< "$previa")"
            printf '%s\n' "${lineas[@]}" | grep -q "environment=\"$amb\"" || echo "$previa"
        done
    fi
} > "$tmp"
chmod 644 "$tmp"
mv -f "$tmp" "$prom"

[ "$fallas" -eq 0 ] || exit 1
