#!/usr/bin/env bash
# local debug APK build. usage: ./build-local.sh
set -euo pipefail
# JDK 17: Homebrew prefix differs per machine (Intel /usr/local, ARM /opt/homebrew).
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/javac" ]]; then
  for _jh in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17; do
    [[ -x "$_jh/bin/javac" ]] && export JAVA_HOME="$_jh" && break
  done
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}/usr/local/bin:/opt/homebrew/bin:$PATH"
[[ -x "${JAVA_HOME:-}/bin/javac" ]] || { echo "JDK 17 not found (looked in /opt/homebrew and /usr/local opt/openjdk@17)"; exit 2; }

# Android SDK: prefer local.properties sdk.dir, then the two known installs.
if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  for _sdk in \
    "$(sed -n 's/^sdk\.dir=//p' "$(dirname "$0")/local.properties" 2>/dev/null)" \
    /opt/homebrew/share/android-commandlinetools \
    /usr/local/share/android-commandlinetools \
    "$HOME/Library/Android/sdk"; do
    [[ -n "$_sdk" && -d "$_sdk/platforms" ]] && export ANDROID_SDK_ROOT="$_sdk" && break
  done
fi
export ANDROID_HOME="${ANDROID_SDK_ROOT:-}"
[[ -d "${ANDROID_HOME:-}/platforms" ]] || { echo "Android SDK not found (set ANDROID_SDK_ROOT)"; exit 2; }
GRADLE_BIN="/tmp/gradle-8.7/bin/gradle"
if [[ ! -x "$GRADLE_BIN" ]]; then
  # /tmp wipes on reboot — fall back to the committed wrapper instead of
  # failing (a failed build must never leave a stale APK to be staged).
  GRADLE_BIN="$(dirname "$0")/gradlew"
fi
if [[ ! -x "$GRADLE_BIN" && ! -f "$GRADLE_BIN" ]]; then
  echo "gradle 8.7 missing at /tmp/gradle-8.7 and no wrapper present:"
  echo "  cd /tmp && curl -sL -o gradle.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip && unzip -q gradle.zip"
  exit 2
fi
cd "$(dirname "$0")"
"$GRADLE_BIN" assembleDebug --no-daemon
APK="app/build/outputs/apk/debug/app-debug.apk"
echo "APK: $(pwd)/$APK"
