#!/usr/bin/env bash
# Everything that can be checked before a phone is involved. Run it before every push:
#
#   tooling/check.sh            unit tests + release build
#   tooling/check.sh --device   ...and install it and prove a read works (tooling/smoke.sh)
#
# Needs a JDK 17 and an Android SDK. Both are found automatically here, and can be pointed
# elsewhere with JAVA_HOME / ANDROID_HOME.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

die() { echo "check: $*" >&2; exit 1; }

if [[ -z ${JAVA_HOME:-} ]]; then
  for candidate in "$HOME"/tools/jdk-17* /opt/homebrew/opt/openjdk@17 /Library/Java/JavaVirtualMachines/temurin-17*; do
    [[ -x $candidate/bin/javac || -x $candidate/Contents/Home/bin/javac ]] || continue
    if [[ -x $candidate/Contents/Home/bin/javac ]]; then JAVA_HOME="$candidate/Contents/Home"; else JAVA_HOME=$candidate; fi
    break
  done
fi
[[ -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/javac ]] || die "no JDK 17 found; set JAVA_HOME"
export JAVA_HOME

if [[ -z ${ANDROID_HOME:-} ]]; then
  for candidate in "$HOME/Library/Android/sdk" /usr/local/share/android-sdk; do
    [[ -d $candidate ]] && ANDROID_HOME=$candidate && break
  done
fi
[[ -n ${ANDROID_HOME:-} && -d $ANDROID_HOME ]] || die "no Android SDK found; set ANDROID_HOME"
export ANDROID_HOME
# NOT platform-tools on PATH: its adb would shadow the one already installed, and two adb versions
# kill each other's server — which drops the device mid-check.

echo "→ unit tests (JVM: the tree rules, the JSON, the protocol, the sockets)"
./gradlew --console=plain test

echo "→ assembleRelease"
./gradlew --console=plain assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
[[ -f $apk ]] || die "no APK at $apk"

aapt2=$(ls -1d "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)
if [[ -n $aapt2 ]]; then
  badge=$("$aapt2" dump badging "$apk" | sed -n '/^package:/p')
  echo "  $badge"
  case "$badge" in
    *"name='io.github.zh_tx3.a11ybridge'"*) ;;
    *) die "unexpected package: $badge" ;;
  esac
fi

echo "  $(du -h "$apk" | cut -f1)  sha256=$(shasum -a 256 "$apk" | cut -d' ' -f1)"

if [[ ${1:-} == --device ]]; then
  echo
  tooling/smoke.sh "$apk"
fi
