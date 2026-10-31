#!/usr/bin/env bash
# The gate no JVM test can replace: install on a real phone, switch the service on, and prove a
# read comes back. Two of the four defects this tool shipped would have been caught here only —
# a Context touched before attachBaseContext, and a port another app already owned.
#
#   tooling/smoke.sh [apk]
#
# Leaves the service enabled and the listener closed; nothing else on the phone is touched apart
# from this package's own install.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

apk=${1:-app/build/outputs/apk/release/app-release.apk}
pkg=io.github.zh_tx3.a11ybridge
component="$pkg/$pkg.BridgeService"
serial=()

die() { echo "smoke: $*" >&2; exit 1; }
adbx() { adb ${serial[@]+"${serial[@]}"} "$@"; }

[[ -f $apk ]] || die "no APK at $apk (tooling/check.sh builds one)"
for _ in $(seq 1 10); do
  adbx get-state >/dev/null 2>&1 && break
  sleep 0.5
done
adbx devices | tail -n +2 | grep -q "device$" || die "no device (adb devices); two adb versions fight over the server"

echo "→ install $apk"
if ! adbx install -r "$apk" >/dev/null 2>&1; then
  # A CI build is not upgradeable over a locally signed one: different keystore.
  echo "  replace refused (signature), uninstalling first"
  adbx uninstall "$pkg" >/dev/null 2>&1 || true
  adbx install "$apk" >/dev/null 2>&1 || die "install failed"
fi

echo "→ enable the service"
cur=$(adbx shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$cur" in
  *"$component"*) ;;
  *) adbx shell settings put secure enabled_accessibility_services "${cur:+$cur:}$component" ;;
esac
adbx shell settings put secure accessibility_enabled 1

echo "→ wait for the process"
for _ in $(seq 1 30); do
  adbx shell ps -A 2>/dev/null | grep -q "$pkg" && break
  sleep 0.5
done
adbx shell ps -A 2>/dev/null | grep -q "$pkg" || die "the process never started (adb logcat -s A11yBridge)"

if adbx shell dumpsys accessibility 2>/dev/null | grep "Crashed services" | grep -q "$pkg"; then
  die "the service is in Crashed services (adb logcat -b crash)"
fi
echo "  running, not crashed"

echo "→ read over the socket"
if ! tooling/a11y-dump.sh --socket -o /tmp/a11y-smoke.json; then
  die "the socket read failed (the diagnosis above says which link is missing)"
fi

grep -q '"schema":"a11y-bridge/1"' /tmp/a11y-smoke.json || die "the body is not this schema"
grep -qE '"nodes":[1-9][0-9]*' /tmp/a11y-smoke.json || die "the tree is empty"
echo "  $(grep -o '"pkg":"[^"]*"' /tmp/a11y-smoke.json | head -1) $(grep -o '"nodes":[0-9]*' /tmp/a11y-smoke.json) $(grep -o '"capture_ms":[0-9]*' /tmp/a11y-smoke.json)"
echo "  $(wc -c < /tmp/a11y-smoke.json | tr -d ' ') bytes"
rm -f /tmp/a11y-smoke.json

echo "→ close the listener"
tooling/a11y-dump.sh --stop 2>/dev/null || true
echo "smoke: ok"
