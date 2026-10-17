#!/bin/bash
# Build and sign the APK without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets \
    --java "$OUT/gen" -o "$OUT/unsigned.apk" "$OUT/res.zip"
javac --release 8 -nowarn -Xlint:-options -classpath "$JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Release signing: SIGNING_KEYSTORE (path) and SIGNING_PASSWORD from the environment
# (CI sets them from repository secrets), else from $XDG_CONFIG_HOME/tv-ambient/signing.env.
# One fixed key lets every build install over the last. Without one, fall back to a
# throwaway debug key, whose builds need `adb uninstall` before installing another.
ENV_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/tv-ambient/signing.env"
if [[ -z ${SIGNING_KEYSTORE:-} && -r $ENV_FILE ]]; then
    # shellcheck disable=SC1090
    source "$ENV_FILE"
fi
if [[ -n ${SIGNING_KEYSTORE:-} ]]; then
    KS=$SIGNING_KEYSTORE
    export SIGNING_PASSWORD
    PASS=env:SIGNING_PASSWORD
else
    KS=debug.keystore
    PASS=pass:android
    [[ -f $KS ]] || keytool -genkeypair -keystore $KS -storepass android -keypass android \
        -alias debug -keyalg RSA -validity 10000 -dname "CN=Ambient Dashboard" >/dev/null
    echo "warning: no release key; signing with $KS" >&2
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "$PASS" --out ambient.apk "$OUT/aligned.apk"
echo "built $(pwd)/ambient.apk"
