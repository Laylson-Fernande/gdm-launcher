#!/bin/sh
# Copia os APKs de release de todos os apps para updateApk/ na raiz do projeto.
# Uso: ./copy-to-updateApk.sh          (só copia o que já foi gerado)
#      ./copy-to-updateApk.sh --build  (gera os releases antes de copiar)
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
DEST="$ROOT/updateApk"

if [ "$1" = "--build" ]; then
    "$ROOT/gradlew" -p "$ROOT" assembleRelease
fi

mkdir -p "$DEST"

count=0
for apk in "$ROOT"/*/build/outputs/apk/release/*.apk; do
    [ -f "$apk" ] || continue
    cp "$apk" "$DEST/"
    echo "copiado: $(basename "$apk")"
    count=$((count + 1))
done

if [ "$count" -eq 0 ]; then
    echo "Nenhum APK de release encontrado. Rode ./copy-to-updateApk.sh --build" >&2
    exit 1
fi
echo "$count APK(s) em $DEST"
