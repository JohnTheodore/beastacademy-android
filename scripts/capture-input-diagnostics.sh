#!/usr/bin/env bash
# Read-only capture. Never reload, stop, reinstall, or navigate the app here.
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 ADB_SERIAL [OUTPUT_DIRECTORY]" >&2
  exit 2
fi
serial=$1
output=${2:-"/tmp/beast-input-$(date +%Y%m%d-%H%M%S)"}
package=com.example.beast_academy_apk
mkdir -p "$output"
adb -s "$serial" get-state > "$output/adb-state.txt"
pid=$(adb -s "$serial" shell pidof "$package" | tr -d '\r')
if [[ -z "$pid" ]]; then
  echo "App is not running; capture will not start or reset it." >&2
  exit 1
fi
adb -s "$serial" logcat -d -v threadtime --pid="$pid" > "$output/app-logcat.txt"
adb -s "$serial" shell dumpsys input > "$output/input.txt"
adb -s "$serial" shell dumpsys window > "$output/window.txt"
adb -s "$serial" shell dumpsys webviewupdate > "$output/webview-provider.txt"
adb -s "$serial" exec-out run-as "$package" tar -cf - files/input-trace > "$output/private-trace.tar"
echo "Saved diagnostics to $output. App process $pid was left running."
