#!/usr/bin/env bash
# IMP-04. Prueba de publicar-frontend.sh con zips falsos. Corre en el job "nginx" de .github/workflows/ci.yml:
#   bash ops/nginx/publicar-frontend.test.sh
set -euo pipefail

script="$(cd "$(dirname "$0")" && pwd)/publicar-frontend.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
base="$work/frontend"
failures=0

check() {
  local description=$1
  shift
  if "$@"; then
    echo "ok    $description"
  else
    echo "FALLA $description"
    failures=$((failures + 1))
  fi
}

# Un zip como el de release.yml: la carpeta dist con index.html y un chunk propio.
make_zip() {
  local version=$1 chunk=$2
  local src="$work/src-$version"
  mkdir -p "$src/dist/assets"
  echo "<script src=\"/assets/$chunk\"></script>" > "$src/dist/index.html"
  echo "// $version" > "$src/dist/assets/$chunk"
  (cd "$src" && zip -qr "$work/frontend-dist-$version.zip" dist)
  (cd "$work" && sha256sum "frontend-dist-$version.zip" >> SHA256SUMS)
}

for n in 1 2 3 4; do make_zip "v1.0.$n-staging" "index-v$n.js"; done

bash "$script" "$work/frontend-dist-v1.0.1-staging.zip" "$base" "$work/SHA256SUMS" > /dev/null
check "la primera versión queda activa" \
  test "$(readlink "$base/current")" = "releases/frontend-dist-v1.0.1-staging"

bash "$script" "$work/frontend-dist-v1.0.2-staging.zip" "$base" "$work/SHA256SUMS" > /dev/null
check "la segunda versión queda activa" \
  test "$(readlink "$base/current")" = "releases/frontend-dist-v1.0.2-staging"
check "la nueva hereda el chunk de la anterior (una pestaña vieja lo sigue encontrando)" \
  test -f "$base/current/assets/index-v1.js"
check "la nueva conserva su propio chunk" test -f "$base/current/assets/index-v2.js"
check "lo heredado no cuenta como propio" \
  test "$(cat "$base/current/.assets-propios")" = "assets/index-v2.js"

bash "$script" "$work/frontend-dist-v1.0.3-staging.zip" "$base" "$work/SHA256SUMS" > /dev/null
bash "$script" "$work/frontend-dist-v1.0.4-staging.zip" "$base" "$work/SHA256SUMS" > /dev/null
check "se conservan solo las últimas 3 versiones" \
  test "$(ls "$base/releases" | wc -l)" -eq 3
check "la más vieja se borró" test ! -e "$base/releases/frontend-dist-v1.0.1-staging"
check "la cuarta hereda de las dos anteriores y no de la borrada" \
  bash -c "test -f '$base/current/assets/index-v3.js' && test -f '$base/current/assets/index-v2.js' \
    && test ! -f '$base/current/assets/index-v1.js'"

bash "$script" --activar "$base" frontend-dist-v1.0.3-staging > /dev/null
check "--activar vuelve a una versión publicada" \
  test "$(readlink "$base/current")" = "releases/frontend-dist-v1.0.3-staging"

check "volver a publicar una versión existente falla" \
  bash -c "! bash '$script' '$work/frontend-dist-v1.0.4-staging.zip' '$base' > /dev/null 2>&1"

echo "0000000000000000000000000000000000000000000000000000000000000000  frontend-dist-v9.9.9-staging.zip" \
  > "$work/SHA256SUMS.malo"
make_zip "v9.9.9-staging" "index-v9.js"
check "una suma que no coincide no publica" \
  bash -c "! bash '$script' '$work/frontend-dist-v9.9.9-staging.zip' '$base' '$work/SHA256SUMS.malo' > /dev/null 2>&1"
check "y deja activa la versión que estaba" \
  test "$(readlink "$base/current")" = "releases/frontend-dist-v1.0.3-staging"
check "sin carpetas temporales a medias" bash -c "! ls -A '$base/releases' | grep -q '^\.subiendo'"

if [[ $failures -gt 0 ]]; then
  echo "$failures comprobaciones fallaron"
  exit 1
fi
echo "publicar-frontend.sh: todo bien"
