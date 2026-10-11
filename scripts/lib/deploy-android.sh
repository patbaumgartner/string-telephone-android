#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PACKAGE_NAME="com.patbaumgartner.android.stringtelephone"
ACTIVITY_NAME=".ui.MainActivity"

if [[ $# -ne 1 || ( "$1" != "device" && "$1" != "emulator" ) ]]; then
    echo "Usage: scripts/deploy-device.sh | scripts/deploy-emulator.sh (no arguments)" >&2
    exit 2
fi
target="$1"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${HOME}/Android/Sdk}}"
if [[ -x "$sdk/platform-tools/adb" ]]; then
    adb="$sdk/platform-tools/adb"
elif command -v adb >/dev/null 2>&1; then
    adb="$(command -v adb)"
else
    echo "adb not found. Set ANDROID_HOME or add Android platform-tools to PATH." >&2
    exit 1
fi

"$adb" start-server >/dev/null
listing="$("$adb" devices)"
serial="${ANDROID_SERIAL:-}"
if [[ "$target" == "device" ]]; then
    serial="${DEVICE_SERIAL:-$serial}"
else
    serial="${EMULATOR_SERIAL:-$serial}"
fi

devices=()
while read -r candidate state _; do
    [[ "$state" == "device" ]] || continue
    if [[ "$target" == "emulator" && "$candidate" == emulator-* ]] ||
       [[ "$target" == "device" && "$candidate" != emulator-* ]]; then
        devices+=("$candidate")
    fi
done <<< "$listing"

if [[ -z "$serial" ]]; then
    if [[ ${#devices[@]} -ne 1 ]]; then
        echo "Expected one ready $target; found ${#devices[@]}." >&2
        echo "Connect a device or start an AVD in Android Studio, then select with" >&2
        echo "DEVICE_SERIAL or EMULATOR_SERIAL if more than one is running." >&2
        printf '%s\n' "$listing" >&2
        exit 1
    fi
    serial="${devices[0]}"
fi

matched=false
for candidate in "${devices[@]}"; do
    if [[ "$candidate" == "$serial" ]]; then
        matched=true
        break
    fi
done
if [[ "$matched" != true ]]; then
    echo "Selected target $serial is not a ready $target (offline, unauthorized, or wrong type)." >&2
    printf '%s\n' "$listing" >&2
    exit 1
fi

boot_completed="$("$adb" -s "$serial" shell getprop sys.boot_completed)"
if [[ "${boot_completed//$'\r'/}" != "1" ]]; then
    echo "Target $serial has not finished booting. Wait for its home screen and retry." >&2
    exit 1
fi

cd "$ROOT_DIR"
echo "Building String Telephone for $serial..."
./gradlew --no-daemon :app:assembleDebug

# Gradle installDebug can target multiple devices; install with an explicit adb serial instead.
"$adb" -s "$serial" install -r "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
launch_output="$("$adb" -s "$serial" shell am start -W -n "$PACKAGE_NAME/$ACTIVITY_NAME")"
printf '%s\n' "$launch_output"
if ! grep -q '^Status: ok' <<< "${launch_output//$'\r'/}"; then
    echo "Android did not confirm a successful activity launch on $serial." >&2
    exit 1
fi
echo "String Telephone launched on $serial."
