#!/bin/sh
# Regenerates application icons from the repository root's icon.png (jDeploy's master):
#   macos/AnimatorFX.icns   built with sips and iconutil (macOS only)
#   ../main/resources/com/dlsc/animatorfx/editor/app-icon.png
#                           256 px copy shown in the About dialog (macOS only: sips)
# Usage: sh animatorfx-editor/src/package/make-icons.sh
set -e
cd "$(dirname "$0")"
MASTER=../../../icon.png

if command -v iconutil >/dev/null 2>&1 && command -v sips >/dev/null 2>&1; then
    WORK=$(mktemp -d)
    SET="$WORK/AnimatorFX.iconset"
    trap 'rm -rf "$WORK"' EXIT HUP INT TERM
    mkdir "$SET"
    for size in 16 32 128 256 512; do
        sips -z $size $size "$MASTER" --out "$SET/icon_${size}x${size}.png" >/dev/null
        double=$((size * 2))
        sips -z $double $double "$MASTER" --out "$SET/icon_${size}x${size}@2x.png" >/dev/null
    done
    sips -Z 256 "$MASTER" --out ../main/resources/com/dlsc/animatorfx/editor/app-icon.png >/dev/null
    mkdir -p macos
    iconutil -c icns "$SET" -o macos/AnimatorFX.icns
    echo "Wrote macos/AnimatorFX.icns"
else
    echo "sips/iconutil not found: macos/AnimatorFX.icns was not rebuilt"
fi
