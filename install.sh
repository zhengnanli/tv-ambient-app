#!/bin/bash
# Build, install, and make the app the TV's screensaver.
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
adb install -r ambient.apk
# TCL's TclAppBoot refuses to bind the dream unless the app may auto-start.
adb shell appops set com.ambient.ambient APP_AUTO_START allow
adb shell settings put secure screensaver_components com.ambient.ambient/.AmbientDream
adb shell settings put secure screensaver_enabled 1
echo "installed; start it now with: adb shell am start -n com.android.systemui/.Somnambulator"
