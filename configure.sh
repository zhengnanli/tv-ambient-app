#!/bin/bash
# Push config.json to the TV. The page reads it each time the screensaver starts.
#
#   configure.sh LAT LON [ICS_URL ...]
#
# LAT/LON drive the weather and the NWS alerts (US only). Picks up the Unsplash key
# from $XDG_CONFIG_HOME/unsplash-wallpaper.env (ACCESS_KEY=...) if it exists; without
# one the screensaver uses Bing's photo of the day.
set -euo pipefail
if [[ $# -lt 2 ]]; then
    sed -n '4p' "$0" | sed 's/^# *//' >&2
    exit 1
fi
LAT=$1 LON=$2
shift 2
CREDS="${XDG_CONFIG_HOME:-$HOME/.config}/unsplash-wallpaper.env"
KEY=""
if [[ -r $CREDS ]]; then
    # shellcheck disable=SC1090
    source "$CREDS"
    KEY="${ACCESS_KEY:-}"
fi
DIR=/sdcard/Android/data/com.ambient.ambient/files
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
jq -n --arg key "$KEY" --argjson lat "$LAT" --argjson lon "$LON" \
    '{unsplashKey: $key, icsUrls: $ARGS.positional, lat: $lat, lon: $lon}' --args "$@" > "$TMP"
adb shell mkdir -p "$DIR"
adb push "$TMP" "$DIR/config.json" >/dev/null
echo "pushed config: unsplash key $([[ -n $KEY ]] && echo set || echo not set), $# calendar(s)"
