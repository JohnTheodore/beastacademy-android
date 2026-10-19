#!/usr/bin/env bash
# Read-only capture. Never reload, stop, reinstall, unlock, or navigate the app here.
set -euo pipefail
umask 077
if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 ADB_SERIAL [OUTPUT_DIRECTORY]" >&2
  exit 2
fi
serial=$1
output=${2:-"/tmp/beast-input-$(date +%Y%m%d-%H%M%S)"}
package=${BEAST_PACKAGE:-com.example.beast_academy_apk}
case "$package" in
  com.example.beast_academy_apk|com.example.beast_academy_apk.inputlab) ;;
  *) echo "Unexpected Beast Academy package: $package" >&2; exit 2 ;;
esac
mkdir -p "$output"
chmod 700 "$output"
adb -s "$serial" get-state > "$output/adb-state.txt"
pid=$(adb -s "$serial" shell pidof "$package" | tr -d '\r')
if [[ -z "$pid" ]]; then
  echo "App is not running; capture will not start or reset it." >&2
  exit 1
fi
printf 'package=%s\npid_before=%s\nhost_time=%s\n' "$package" "$pid" "$(date -u +%FT%TZ)" > "$output/capture-state.txt"
# Export the persistent ring first, before slower system snapshots or further interactions.
if adb -s "$serial" exec-out content read --uri "content://$package.input-diagnostics/snapshot" > "$output/private-trace.zip" 2> "$output/diagnostic-export-error.txt" &&
    unzip -t "$output/private-trace.zip" > "$output/diagnostic-export-validation.txt" 2>&1; then
  printf 'private_trace=zip\n' >> "$output/capture-state.txt"
else
  # Backward-compatible with the already installed 0.7.6 debug build.
  mv "$output/private-trace.zip" "$output/diagnostic-export-output.txt"
  if adb -s "$serial" exec-out run-as "$package" tar -cf - files/input-trace > "$output/private-trace.tar" 2> "$output/run-as-error.txt"; then
    printf 'private_trace=legacy_tar\n' >> "$output/capture-state.txt"
  else
    printf 'private_trace=UNAVAILABLE\n' >> "$output/capture-state.txt"
    echo "Private trace export unavailable; preserving system evidence. Do not reinstall to enable it." >&2
  fi
fi
adb -s "$serial" logcat -d -v threadtime --pid="$pid" > "$output/app-logcat.txt"
adb -s "$serial" shell dumpsys input > "$output/input.txt"
adb -s "$serial" shell dumpsys window > "$output/window.txt"
adb -s "$serial" shell dumpsys webviewupdate > "$output/webview-provider.txt"
adb -s "$serial" shell dumpsys power > "$output/power.txt"
adb -s "$serial" shell dumpsys package "$package" > "$output/package.txt"
adb -s "$serial" exec-out screencap -p > "$output/screenshot.png"
# Preserve the installed native code as well as its version label: local builds can
# share a version number while containing different attempted fixes.
adb -s "$serial" shell pm path "$package" > "$output/package-paths.txt"
while IFS= read -r package_path; do
  package_path=$(printf '%s' "$package_path" | tr -d '\r')
  apk_path=${package_path#package:}
  if [[ "$apk_path" == */base.apk ]]; then
    adb -s "$serial" pull "$apk_path" "$output/installed-base.apk" > "$output/apk-pull.txt" 2>&1 || true
    break
  fi
done < "$output/package-paths.txt"
# Read-only CDP state where the installed build already exposes debugging. Never enable it here.
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
forward_port=""
cleanup() {
  for artifact in "$output"/*; do [[ ! -f "$artifact" ]] || chmod 600 "$artifact"; done
  if [[ -n "$forward_port" ]]; then adb -s "$serial" forward --remove "tcp:$forward_port" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
node_ready=false
if command -v node >/dev/null 2>&1 && node -e 'process.exit(typeof WebSocket === "function" && typeof fetch === "function" ? 0 : 1)' >/dev/null 2>&1; then node_ready=true; fi
if [[ "$node_ready" == true ]] || command -v curl >/dev/null 2>&1; then
  if forward_port=$(adb -s "$serial" forward tcp:0 "localabstract:webview_devtools_remote_$pid" 2> "$output/cdp-forward-error.txt"); then
    forward_port=$(printf '%s' "$forward_port" | tr -d '\r\n')
    if [[ "$node_ready" == true && -f "$script_dir/capture-webview-state.mjs" ]]; then
      node "$script_dir/capture-webview-state.mjs" "http://127.0.0.1:$forward_port" "$output" > "$output/cdp-capture.txt" 2>&1 || true
    elif curl -fsS --max-time 3 "http://127.0.0.1:$forward_port/json/list" > "$output/cdp-targets.json" 2> "$output/cdp-capture.txt"; then
      curl -fsS --max-time 3 "http://127.0.0.1:$forward_port/json/version" > "$output/cdp-version.json" 2>> "$output/cdp-capture.txt" || true
    fi
  fi
fi
pid_after=$(adb -s "$serial" shell pidof "$package" | tr -d '\r')
printf 'pid_after=%s\n' "$pid_after" >> "$output/capture-state.txt"
if [[ "$pid_after" != "$pid" ]]; then
  echo "App PID changed during capture ($pid -> $pid_after); preserve this evidence and investigate the exit." >&2
  exit 1
fi
checksum=()
if command -v sha256sum >/dev/null 2>&1; then checksum=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then checksum=(shasum -a 256)
fi
if [[ ${#checksum[@]} -gt 0 ]]; then
  (cd "$output"
    for artifact in *; do
      [[ "$artifact" == SHA256SUMS || ! -f "$artifact" ]] || "${checksum[@]}" "$artifact"
    done) > "$output/SHA256SUMS"
fi
echo "Saved diagnostics to $output. App process $pid was left running."
