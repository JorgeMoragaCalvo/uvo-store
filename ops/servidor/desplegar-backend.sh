#!/usr/bin/env bash
# IMP-05 (C02). Despliega el jar de un release en un ambiente ya provisionado.
#
#   sudo bash ops/servidor/desplegar-backend.sh uvo-store-v1.4.0.jar staging SHA256SUMS
#   sudo bash ops/servidor/desplegar-backend.sh --activar staging uvo-store-v1.3.2      # volver a una versión anterior
#
# 1. Comprueba el jar contra SHA256SUMS del release (sin SHA256SUMS no despliega, salvo SIN_SUMA=1 a sabiendas).
# 2. Lo copia a /opt/uvostore-<ambiente>/releases/<versión>.jar. Una versión que ya existe con otro contenido es un
#    error: el mismo nombre tiene que ser siempre el mismo artefacto.
# 3. Cambia el symlink current.jar de forma atómica, deja SENTRY_RELEASE=<versión> en el archivo de entorno y
#    reinicia el servicio. Flyway migra al arrancar, con el rol migrador.
# 4. Espera /actuator/health/readiness (por omisión 180 s, ESPERA_SEGUNDOS).
#
# Si no queda listo NO vuelve atrás solo: muestra el final del log y el comando para volver a la versión anterior.
# Volver atrás es seguro si la versión nueva no aplicó migraciones; si las aplicó, la anterior puede no arrancar
# con el esquema nuevo (Hibernate valida el esquema), y eso se decide con el procedimiento de IMP-09, no aquí.
#
# Conserva las últimas CONSERVAR versiones (3), nunca borra la activa.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
. "$DIR/comun.sh"

CONSERVAR="${CONSERVAR:-3}"
ESPERA_SEGUNDOS="${ESPERA_SEGUNDOS:-180}"

uso() {
    die "uso: $0 <jar> <ambiente> [SHA256SUMS]  |  $0 --activar <ambiente> <versión>"
}

# Cambia current.jar sin que exista un instante en que no apunte a nada.
activar_symlink() {
    local opt="$1" version="$2"
    ln -s "releases/$version.jar" "$opt/current.jar.nuevo"
    mv -T "$opt/current.jar.nuevo" "$opt/current.jar"
}

version_activa() {
    local opt="$1"
    [ -L "$opt/current.jar" ] || return 0
    basename "$(readlink "$opt/current.jar")" .jar
}

reiniciar_y_esperar() {
    local ambiente="$1" version="$2" anterior="$3" env_file puerto servicio
    env_file="$(archivo_env "$ambiente")"
    puerto="$(valor_env "$env_file" SERVER_PORT)"
    servicio="uvostore-$ambiente"

    # Sentry atribuye los errores a la versión que corre (SentryBootstrap exige SENTRY_RELEASE con DSN). Es el tag,
    # sin el prefijo del jar, igual que VITE_SENTRY_RELEASE del frontend (release.yml).
    fijar_env "$env_file" SENTRY_RELEASE "${version#uvo-store-}"

    info "reiniciando $servicio con $version"
    systemctl reset-failed "$servicio" 2> /dev/null || true
    systemctl restart "$servicio"
    if esperar_readiness "$puerto" "$ESPERA_SEGUNDOS" "$servicio"; then
        info "$servicio listo con $version"
        return 0
    fi

    echo "ERROR: $servicio no quedó listo con $version. Final del log:" >&2
    journalctl -u "$servicio" -n 60 --no-pager >&2 || true
    if [ -n "$anterior" ] && [ "$anterior" != "$version" ]; then
        cat >&2 << FIN

Para volver a la versión anterior:
  sudo bash $0 --activar $ambiente $anterior
Antes, revisa si $version aplicó migraciones nuevas (tabla flyway_schema_history): con migraciones aplicadas, la
versión anterior puede no arrancar y hay que seguir el procedimiento de recuperación (docs/despliegue-backend.md).
FIN
    fi
    exit 1
}

podar() {
    local opt="$1" activa archivo
    activa="$(version_activa "$opt")"
    # Las más nuevas primero, por fecha de modificación (la de la copia, que es la del despliegue).
    ls -1t "$opt/releases/"*.jar 2> /dev/null | tail -n +$((CONSERVAR + 1)) | while IFS= read -r archivo; do
        [ "$(basename "$archivo" .jar)" = "$activa" ] && continue
        info "borrando $(basename "$archivo")"
        rm -f "$archivo"
    done
}

# ---- --activar -----------------------------------------------------------------------------------------------
if [ "${1:-}" = "--activar" ]; then
    [ $# -eq 3 ] || uso
    AMBIENTE="$2"
    VERSION="$3"
    validar_ambiente "$AMBIENTE"
    requerir_root
    OPT="${UVOSTORE_OPT_PREFIX}$AMBIENTE"
    [ -f "$OPT/releases/$VERSION.jar" ] || die "no existe $OPT/releases/$VERSION.jar (versiones: $(ls "$OPT/releases" 2> /dev/null | tr '\n' ' '))"
    ANTERIOR="$(version_activa "$OPT")"
    activar_symlink "$OPT" "$VERSION"
    reiniciar_y_esperar "$AMBIENTE" "$VERSION" "$ANTERIOR"
    exit 0
fi

# ---- despliegue ------------------------------------------------------------------------------------------------
[ $# -ge 2 ] && [ $# -le 3 ] || uso
JAR="$1"
AMBIENTE="$2"
SUMAS="${3:-}"
validar_ambiente "$AMBIENTE"
requerir_root
requerir_comandos systemctl curl sha256sum journalctl
[ -f "$JAR" ] || die "no existe $JAR"
OPT="${UVOSTORE_OPT_PREFIX}$AMBIENTE"
[ -d "$OPT/releases" ] && [ -f "$(archivo_env "$AMBIENTE")" ] || die "$AMBIENTE no está provisionado (sudo bash ops/servidor/provisionar.sh $AMBIENTE)"

NOMBRE="$(basename "$JAR")"
VERSION="${NOMBRE%.jar}"
[[ "$VERSION" =~ ^[A-Za-z0-9._-]+$ ]] || die "nombre de jar inesperado: $NOMBRE"

if [ -n "$SUMAS" ]; then
    esperada="$(awk -v f="$NOMBRE" '$2 == f || $2 == "*" f { print $1 }' "$SUMAS")"
    [ -n "$esperada" ] || die "$NOMBRE no está en $SUMAS"
    real="$(sha256sum "$JAR" | cut -d' ' -f1)"
    [ "$esperada" = "$real" ] || die "la suma de $NOMBRE no coincide con $SUMAS: no se despliega"
    info "suma verificada"
elif [ "${SIN_SUMA:-}" = 1 ]; then
    aviso "desplegando sin SHA256SUMS (SIN_SUMA=1)"
else
    die "falta SHA256SUMS del release (o SIN_SUMA=1 para desplegar sin verificar, a sabiendas)"
fi

DESTINO="$OPT/releases/$VERSION.jar"
if [ -f "$DESTINO" ]; then
    cmp -s "$JAR" "$DESTINO" || die "$DESTINO ya existe con otro contenido: una versión es siempre el mismo artefacto"
else
    install -m 644 -o root -g root "$JAR" "$DESTINO"
fi

ANTERIOR="$(version_activa "$OPT")"
activar_symlink "$OPT" "$VERSION"
reiniciar_y_esperar "$AMBIENTE" "$VERSION" "$ANTERIOR"
podar "$OPT"
