#!/usr/bin/env bash
# IMP-04. Publica un frontend-dist-<tag>-<ambiente>.zip del release sin dejar pestañas rotas.
#
#   publicar-frontend.sh <zip> <base> [SHA256SUMS]   publica y activa la versión
#   publicar-frontend.sh --activar <base> <versión>  vuelve a una versión ya publicada (rollback)
#
# <base> es el directorio del frontend (en producción /srv/uvostore/frontend). Deja:
#   <base>/releases/<versión>/   una carpeta por versión (versión = nombre del zip sin .zip)
#   <base>/current -> releases/<versión>   lo que sirve nginx (root de uvostore.conf)
#   <base>/historial             las versiones publicadas, la más reciente al final
#
# Por qué así (docs/despliegue-frontend.md):
#   - El cambio de versión es un rename de symlink: nginx nunca ve una carpeta a medio copiar.
#   - Una pestaña abierta antes del despliegue tiene el index.html viejo y pedirá sus chunks al navegar. La
#     versión nueva hereda los assets propios de las CONSERVAR-1 anteriores, así que siguen existiendo; si aun
#     así falta uno, la SPA recarga una vez (frontend/src/lib/staleChunk.ts).
#   - Con SHA256SUMS se publica solo lo que salió del release (sha256sum del zip contra su línea).
#
# Variables: CONSERVAR (versiones que se guardan, por defecto 3). Requiere bash, unzip, sha256sum y mv -T
# (GNU coreutils).
set -euo pipefail

CONSERVAR=${CONSERVAR:-3}

fail() {
  echo "publicar-frontend: $*" >&2
  exit 1
}

activate() {
  local base=$1 version=$2
  [[ -f "$base/releases/$version/index.html" ]] || fail "no existe la versión $version en $base/releases"
  ln -s "releases/$version" "$base/current.nuevo"
  mv -T "$base/current.nuevo" "$base/current"
  echo "publicar-frontend: activa $version"
}

if [[ ${1:-} == "--activar" ]]; then
  [[ $# -eq 3 ]] || fail "uso: $0 --activar <base> <versión>"
  activate "$2" "$3"
  exit 0
fi

[[ $# -ge 2 && $# -le 3 ]] || fail "uso: $0 <zip> <base> [SHA256SUMS]"
zip=$1
base=$2
sums=${3:-}
[[ -f $zip ]] || fail "no existe $zip"
name=$(basename "$zip")
version=${name%.zip}
[[ $version != "$name" ]] || fail "$name no termina en .zip"

if [[ -n $sums ]]; then
  expected=$(awk -v f="$name" '$2 == f || $2 == "*" f { print $1 }' "$sums")
  [[ -n $expected ]] || fail "$name no aparece en $sums"
  actual=$(sha256sum "$zip" | awk '{ print $1 }')
  [[ $expected == "$actual" ]] || fail "la suma de $name no coincide con $sums: no se publica"
fi

mkdir -p "$base/releases"
dest="$base/releases/$version"
[[ ! -e $dest ]] || fail "$version ya está publicada; para volver a ella: $0 --activar $base $version"

tmp=$(mktemp -d "$base/releases/.subiendo-XXXXXX")
trap 'rm -rf "$tmp"' EXIT
unzip -q "$zip" -d "$tmp"
# release.yml comprime la carpeta dist entera.
[[ -f "$tmp/dist/index.html" ]] || fail "$name no trae dist/index.html"

# Los assets propios de esta versión, antes de heredar nada: es lo que heredarán las siguientes.
(cd "$tmp/dist" && find assets -type f 2>/dev/null | sort) > "$tmp/dist/.assets-propios"

# Hereda los assets propios de las versiones anteriores que se conservan (sin pisar los de esta).
touch "$base/historial"
while IFS= read -r previous; do
  list="$base/releases/$previous/.assets-propios"
  [[ -f $list ]] || continue
  while IFS= read -r asset; do
    [[ -n $asset && ! -e "$tmp/dist/$asset" ]] || continue
    mkdir -p "$(dirname "$tmp/dist/$asset")"
    cp -p "$base/releases/$previous/$asset" "$tmp/dist/$asset"
  done < "$list"
done < <(tail -n $((CONSERVAR - 1)) "$base/historial")

mv "$tmp/dist" "$dest"
echo "$version" >> "$base/historial"
activate "$base" "$version"

# Poda: fuera de las últimas CONSERVAR del historial, y nunca la activa (puede ser una vuelta atrás).
active=$(basename "$(readlink "$base/current")")
tail -n "$CONSERVAR" "$base/historial" > "$tmp/conservar"
for dir in "$base"/releases/*/; do
  old=$(basename "$dir")
  [[ $old == "$active" ]] && continue
  grep -qxF "$old" "$tmp/conservar" && continue
  rm -rf "$dir"
  echo "publicar-frontend: borrada $old"
done
tail -n "$CONSERVAR" "$base/historial" > "$base/historial.nuevo"
mv "$base/historial.nuevo" "$base/historial"
