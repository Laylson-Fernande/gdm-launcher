#!/bin/sh
# Copia os APKs de release de todos os apps do GDM K4811 App suite para o pen drive/cartão.
# Destino: variável APKS_DIR, ou /Volumes/madera/UpdateApk (nome do volume do pen drive).
# Uso: ./copy-apks.sh          (só copia o que já foi gerado)
#      ./copy-apks.sh --build  (gera os releases antes de copiar)
set -e

DEST="${APKS_DIR:-/Volumes/madera/UpdateApk}"
ROOT="$(cd "$(dirname "$0")" && pwd)"

if [ "$1" = "--build" ]; then
    "$ROOT/gradlew" -p "$ROOT" assembleRelease
fi

if [ ! -d "$(dirname "$DEST")" ]; then
    echo "Destino $(dirname "$DEST") não existe (pen drive não montado?)." >&2
    exit 1
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
    echo "Nenhum APK de release encontrado. Rode ./copy-apks.sh --build" >&2
    exit 1
fi
echo "$count APK(s) em $DEST"
