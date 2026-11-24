#!/usr/bin/env bash
# Baja la distribución de Maven que pide el wrapper (.mvn/wrapper) antes de compilar.
#
# El wrapper only-script la baja con un solo intento de wget, y Maven Central a veces les falla a los runners
# de GitHub: el job caía con «wget: Failed to fetch …apache-maven-…-bin.zip» antes de compilar. Aquí se
# reintenta, alternando Maven Central con su espejo de Google. La distribución queda en ~/.m2/wrapper, que
# la acción .github/actions/maven-wrapper guarda en caché, y los ./mvnw siguientes la reutilizan sin red.
#
# Uso: bash .github/scripts/traer-maven.sh [directorio con mvnw]   (por omisión, el actual)
set -uo pipefail

cd "${1:-.}" || exit 1

ESPEJOS=("" "https://maven-central.storage-download.googleapis.com/maven2")
INTENTOS=6

for ((intento = 1; intento <= INTENTOS; intento++)); do
    espejo="${ESPEJOS[$(((intento - 1) % ${#ESPEJOS[@]}))]}"
    if [ -n "$espejo" ]; then
        MVNW_REPOURL="$espejo" ./mvnw --batch-mode --version > /dev/null && exit 0
        origen="$espejo"
    else
        env -u MVNW_REPOURL ./mvnw --batch-mode --version > /dev/null && exit 0
        origen="Maven Central"
    fi
    if ((intento < INTENTOS)); then
        echo "::warning::No se pudo bajar Maven desde $origen (intento $intento de $INTENTOS); se reintenta" >&2
        sleep $((intento * 10))
    fi
done

echo "::error::No se pudo bajar Maven tras $INTENTOS intentos (Maven Central y espejo de Google)" >&2
exit 1
