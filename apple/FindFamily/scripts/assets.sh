#!/usr/bin/env bash
# assets.sh compile   (Mac only) rebuild Resources/Assets.car from FindFamilyiOS/Assets.xcassets
# assets.sh check     (any OS)   fail if Assets.car is stale relative to the .xcassets sources
set -euo pipefail

cd "$(dirname "$0")/.."
SRC=FindFamilyiOS/Assets.xcassets
OUT=Resources
HASH_FILE="$OUT/Assets.xcassets.sha256"

sources_hash() {
    local sha
    if command -v sha256sum >/dev/null; then sha="sha256sum"; else sha="shasum -a 256"; fi
    find "$SRC" -type f ! -name '.DS_Store' -print0 | LC_ALL=C sort -z \
        | xargs -0 $sha | $sha | cut -d' ' -f1
}

case "${1:-}" in
    compile)
        mkdir -p "$OUT"
        rm -f "$OUT/Assets.car" "$OUT/assets-partial-Info.plist"
        xcrun actool "$SRC" \
            --compile "$OUT" \
            --platform iphoneos \
            --minimum-deployment-target 18.0 \
            --target-device iphone \
            --app-icon AppIcon \
            --accent-color AccentColor \
            --output-partial-info-plist "$OUT/assets-partial-Info.plist" \
            --output-format human-readable-text --notices --warnings --errors
        sources_hash > "$HASH_FILE"
        echo "assets: wrote $OUT/Assets.car ($(cat "$HASH_FILE"))"
        ;;
    check)
        expected=$(cat "$HASH_FILE")
        actual=$(sources_hash)
        if [ "$expected" != "$actual" ]; then
            echo "assets: $OUT/Assets.car is stale; run apple/FindFamily/scripts/assets.sh compile on a Mac" >&2
            exit 1
        fi
        echo "assets: up to date"
        ;;
    *)
        echo "usage: $0 compile|check" >&2
        exit 2
        ;;
esac
