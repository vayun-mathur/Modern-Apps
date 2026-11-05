#!/usr/bin/env bash
# stamp-version.sh [Info.plist]
# Writes Modern-Apps version.txt into the app's Info.plist (in place):
#   CFBundleVersion            = line 1 (numeric code, e.g. 20261105)
#   CFBundleShortVersionString = line 2 minus leading "v" and trailing letters (v2.6.11b -> 2.6.11),
#                                because Apple only accepts dot-separated integers.
# Run on a throwaway checkout (the release builder clones fresh).
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
PLIST="${1:-$HERE/FindFamilyiOS/Info.plist}"
VERSION_FILE="$HERE/../../version.txt"

CODE=$(sed -n '1p' "$VERSION_FILE" | tr -d '[:space:]')
NAME=$(sed -n '2p' "$VERSION_FILE" | tr -d '[:space:]')
SHORT=$(printf '%s' "$NAME" | sed -E 's/^v//; s/[^0-9.]+$//')

[[ "$CODE" =~ ^[0-9]+$ ]] || { echo "stamp-version: bad version code '$CODE'" >&2; exit 1; }
[[ "$SHORT" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] || { echo "stamp-version: cannot map '$NAME' to an Apple version" >&2; exit 1; }

python3 - "$PLIST" "$SHORT" "$CODE" <<'EOF'
import plistlib, sys
path, short, code = sys.argv[1:]
with open(path, "rb") as f:
    info = plistlib.load(f)
info["CFBundleShortVersionString"] = short
info["CFBundleVersion"] = code
with open(path, "wb") as f:
    plistlib.dump(info, f)
EOF

echo "stamp-version: $SHORT ($CODE) -> $PLIST"
