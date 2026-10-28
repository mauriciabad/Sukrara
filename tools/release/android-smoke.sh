#!/bin/sh
# Sukrara — https://github.com/mauriciabad/sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# The Android release gate, on an emulator only (never a phone):
#   tools/release/android-smoke.sh [--final] [--no-build] [--keep-emulator] [--wait SECONDS]
# 1. builds the signed release APK (the Sukrara release key; no key → stop, never the debug key) and runs
#    verify-apk.sh on it
# 2. fresh install: launches, renders its first screen, survives being recreated from saved state, stays alive
# 3. before setup nothing starts: no notification at all, and no alert also after the clock jumps 3 h ahead (past
#    every no-data delay)
# 4. logcat of the app's uid has no crash and no R8 / JNI / JNA / UniFFI / serialization / Room breakage
# --final (the release gate at the end): every WARN (a feature not wired yet) is a FAIL.
# Logs, dumps and screenshots (fresh install, no data) stay in $TMPDIR/sukrara-smoke.<time>/. Exit 0 only when
# nothing FAILed.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/lib.sh"
root="$(repo_root)"

final=0 build=1 keep=0 wait_s=180
while [ $# -gt 0 ]; do
  case "$1" in
    --final) final=1 ;;
    --no-build) build=0 ;;
    --keep-emulator) keep=1 ;;
    --wait) wait_s="${2:?--wait SECONDS}"; shift ;;
    -h | --help) sed -n '5,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option $1 (--help)" >&2; exit 2 ;;
  esac
  shift
done

APP=app.mauri.sukrara
OWNER_PHONE=62240DLCH0005W
AVD=release_smoke
PORT=5600
SERIAL="emulator-$PORT"
IMAGE=system-images/android-37.0/google_apis/arm64-v8a   # API 37: Live Updates promote (36.0 can't)
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="$sdk"
JAVA_HOME="$(dirname "$(dirname "$(jdk_tool java)")")"
export JAVA_HOME
export ANDROID_SERIAL="$SERIAL"   # belt and braces: any adb started below talks only to the smoke emulator
adb_bin="$sdk/platform-tools/adb"
out="${TMPDIR:-/tmp}/sukrara-smoke.$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"

fail=0
ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }
warn() { if [ "$final" = 1 ]; then bad "$*"; else echo "WARN $*"; fi; }
die() { echo "FAIL $*" >&2; exit 1; }
# Every adb call names the smoke emulator; nothing here can reach another device. Gradle runs only assemble tasks
# (install* / connected* would target every attached device).
adb() { "$adb_bin" -s "$SERIAL" "$@"; }

# --- emulator -------------------------------------------------------------------------------------------------------
ensure_avd() {
  avd_dir="$HOME/.android/avd/$AVD.avd"
  [ -f "$avd_dir/config.ini" ] && return 0
  [ -d "$sdk/$IMAGE" ] || die "no $IMAGE in $sdk (install it with the SDK manager)"
  mkdir -p "$avd_dir"
  printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-37.0\n' "$avd_dir" > "$HOME/.android/avd/$AVD.ini"
  cat > "$avd_dir/config.ini" <<EOF
AvdId=$AVD
avd.ini.displayname=Sukrara release smoke
abi.type=arm64-v8a
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.ramSize=4096
disk.dataPartition.size=6G
hw.lcd.density=420
hw.lcd.width=1080
hw.lcd.height=2400
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=auto
PlayStore.enabled=false
image.sysdir.1=$IMAGE/
tag.id=google_apis
target=android-37.0
EOF
  echo "created AVD $AVD ($IMAGE)"
}

started_emulator=0
# Starts the smoke AVD in the background unless it already runs; wait_emulator waits for it after the build.
start_emulator() {
  if "$adb_bin" devices | grep -q "^$SERIAL[[:space:]]*device"; then echo "reusing $SERIAL"; return 0; fi
  ensure_avd
  "$sdk/emulator/emulator" -avd "$AVD" -port "$PORT" -no-snapshot-save -no-window -no-audio -no-boot-anim \
    > "$out/emulator.log" 2>&1 &
  started_emulator=1
}
prop() { adb shell getprop "$1" 2>/dev/null | tr -d '\r'; }
wait_emulator() {
  adb wait-for-device
  i=0
  until [ "$(prop sys.boot_completed)" = 1 ]; do
    i=$((i + 1)); [ $i -lt 120 ] || die "$SERIAL didn't finish booting in 4 min ($out/emulator.log)"; sleep 2
  done
  # The guard: whatever answered on the serial must say it is an emulator.
  if [ "$(prop ro.kernel.qemu)" != 1 ] && [ "$(prop ro.boot.qemu)" != 1 ]; then
    die "refusing $SERIAL: ro.kernel.qemu / ro.boot.qemu aren't 1, this is not an emulator"
  fi
  [ "$(prop ro.serialno)" != "$OWNER_PHONE" ] || die "refusing: that's the owner's phone"
  ok "device $SERIAL is an emulator (API $(prop ro.build.version.sdk))"
}
# Puts back what the smoke changed: activity keeping, the clock (host time), automatic time, unrooted adbd.
restore_device() {
  adb shell settings put global always_finish_activities 0 > /dev/null 2>&1 || true
  adb shell date "$(date +%m%d%H%M%Y.%S)" > /dev/null 2>&1 || true
  adb shell settings put global auto_time 1 > /dev/null 2>&1 || true
  adb unroot > /dev/null 2>&1 || true
}
# adb root restarts adbd: wait until the shell answers as root.
become_root() {
  adb root > /dev/null 2>&1 || return 1
  i=0
  until [ "$(adb shell id -u 2> /dev/null | tr -d '\r')" = 0 ]; do
    i=$((i + 1)); [ $i -lt 30 ] || return 1; sleep 1
  done
}
cleanup() {
  restore_device
  if [ "$started_emulator" = 1 ] && [ "$keep" = 0 ]; then adb emu kill > /dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# --- 1. build + verify ----------------------------------------------------------------------------------------------
apk="$root/apps/android/app/build/outputs/apk/release/app-release.apk"
mapping="$root/apps/android/app/build/outputs/mapping/release/mapping.txt"

start_emulator   # boots while Gradle works

if [ "$build" = 1 ]; then
  ks="$(release_keystore)"
  [ -f "$ks" ] || { echo "FAIL no release keystore at $ks — make it once with tools/release/make-keystore.sh (never fall back to the debug key)" >&2; exit 2; }
  SUKRARA_KEYSTORE_PASSWORD="$(release_password)" || { echo "FAIL no release key password (Keychain item sukrara-android-release)" >&2; exit 2; }
  export SUKRARA_KEYSTORE_PASSWORD
  echo "building the release APK (log: $out/build-release.log)"
  # --no-daemon: the password must not live on in a background daemon's environment
  (cd "$root/apps/android" && ./gradlew --no-daemon :app:assembleRelease --console=plain) > "$out/build-release.log" 2>&1 ||
    die "assembleRelease failed (tail of $out/build-release.log):
$(tail -25 "$out/build-release.log")"
  unset SUKRARA_KEYSTORE_PASSWORD
  ok "assembleRelease"
fi
"$here/verify-apk.sh" "$apk" "$mapping" || fail=1

wait_emulator

# --- 2. install ----------------------------------------------------------------------------------------------------
adb uninstall "$APP" > /dev/null 2>&1 || true
adb install "$apk" > "$out/install.log" 2>&1 || die "installing the release APK failed ($out/install.log)"
ok "release APK installed (fresh: no data)"
pkgs="$(adb shell pm list packages -U | tr -d '\r')"
uid="$(printf '%s\n' "$pkgs" | sed -n "s/^package:$APP uid:\([0-9]*\)$/\1/p")"
[ -n "$uid" ] && ok "pm lists $APP (uid $uid)" || die "pm doesn't list $APP"

# --- 2b. launch -----------------------------------------------------------------------------------------------------
pid_of() { adb shell pidof "$1" 2>/dev/null | tr -d '\r'; }
launch_app() { adb shell am start -W -n "$APP/.MainActivity" > "$out/start.txt" 2>&1 || bad "am start $APP failed ($out/start.txt)"; sleep 5; }
adb shell pm grant "$APP" android.permission.POST_NOTIFICATIONS 2> /dev/null || true
adb logcat -G 16M > /dev/null 2>&1 || true   # the whole run fits: start-up errors must not wrap away
adb logcat -c
launch_app
[ -n "$(pid_of "$APP")" ] && ok "$APP is running after launch" || bad "$APP isn't running 5 s after launch"
adb exec-out screencap -p > "$out/app-first-screen.png"
adb shell uiautomator dump /sdcard/smoke-ui.xml > /dev/null 2>&1 && adb pull /sdcard/smoke-ui.xml "$out/app-first-screen.xml" > /dev/null 2>&1 || true
if grep -q "package=\"$APP\"" "$out/app-first-screen.xml" 2> /dev/null; then
  ok "first screen rendered: $(grep -o 'text="[^"]\{1,\}"' "$out/app-first-screen.xml" | head -3 | tr '\n' ' ')"
else bad "no view of $APP on screen ($out/app-first-screen.png)"; fi

# Saved state (navigation + kotlinx.serialization keys) after the activity is destroyed in the background.
adb shell settings put global always_finish_activities 1
adb shell input keyevent KEYCODE_HOME; sleep 2
launch_app
adb shell settings put global always_finish_activities 0
adb shell uiautomator dump /sdcard/smoke-ui.xml > /dev/null 2>&1 && adb pull /sdcard/smoke-ui.xml "$out/app-restored.xml" > /dev/null 2>&1 || true
if [ -n "$(pid_of "$APP")" ] && grep -q "package=\"$APP\"" "$out/app-restored.xml" 2> /dev/null; then
  ok "$APP renders again after its activity was destroyed (saved state restored)"
else bad "$APP didn't come back after its activity was destroyed ($out/app-restored.xml)"; fi

# --- 3. the window, then 3 h later ----------------------------------------------------------------------------------
echo "watching $APP for ${wait_s}s (no sensor: no alert may fire)"
sleep "$wait_s"
adb exec-out screencap -p > "$out/app-after-window.png"
adb shell dumpsys notification --noredact > "$out/notifications.txt"
# No-data alerts must never start before the first reading: jump past every delay (TIME_SET also wakes the checks).
clock_jumped=0
if become_root; then
  adb shell settings put global auto_time 0
  later="$(adb shell date -d "@$(($(adb shell date +%s | tr -d '\r') + 3 * 3600))" +%m%d%H%M%Y.%S | tr -d '\r')"
  if adb shell date "$later" > /dev/null 2>&1; then
    sleep 30
    adb shell dumpsys notification --noredact > "$out/notifications-3h.txt"
    clock_jumped=1
  fi
fi
[ "$clock_jumped" = 1 ] || warn "couldn't move the emulator clock (adb root?): the 3 h no-alert check didn't run"
restore_device
adb logcat -d --uid="$uid" > "$out/logcat.txt"
adb logcat -d -b crash > "$out/logcat-crash.txt"

[ -n "$(pid_of "$APP")" ] && ok "$APP still running after the window" || bad "$APP died in the window (logcat: $out/logcat.txt)"

# Notification records of a package: the "NotificationRecord(… pkg=<pkg> …" blocks of a dump.
records() { awk -v p="pkg=$1 " 'index($0,"NotificationRecord(")&&index($0,p){on=1;print;next} index($0,"NotificationRecord("){on=0} on' "$2"; }
records "$APP" "$out/notifications.txt" > "$out/app-records.txt"
# A fresh install starts nothing before setup (no sensor, no follow): no monitor, so no notification at all.
if [ -s "$out/app-records.txt" ]; then
  bad "a notification was posted before setup (a fresh install must start nothing): $(grep -o 'channel=[^ ]*' "$out/app-records.txt" | sort -u | tr '\n' ' ')($out/app-records.txt)"
else
  ok "no notification before setup (a fresh install starts nothing)"
fi
no_alert() {
  if records "$APP" "$1" | grep -Eq "channel=alert|fullscreenIntent=PendingIntent"; then
    bad "an alert fired with no sensor ($2)"
  else ok "no alert notification with no sensor ($2)"; fi
}
no_alert "$out/notifications.txt" "after ${wait_s}s"
[ "$clock_jumped" = 1 ] && no_alert "$out/notifications-3h.txt" "3 h later"

# The app's notification channels (its AppSettings block).
channels() { awk -v p="AppSettings: $1 " 'index($0,"AppSettings: "){on=index($0,p)>0} on' "$out/notifications.txt" | grep -o "mId='[^']*'" | sort -u; }
channels "$APP" > "$out/app-channels.txt"
[ -s "$out/app-channels.txt" ] && ok "$APP channels: $(tr '\n' ' ' < "$out/app-channels.txt")" || warn "$APP has no notification channels yet"

# --- 4. logcat ------------------------------------------------------------------------------------------------------
crash="$(grep -h "FATAL EXCEPTION" "$out/logcat.txt" || true; grep -h "Process: $APP," "$out/logcat-crash.txt" || true)"
[ -z "$crash" ] && ok "no crash in logcat" || bad "crash in logcat: $(printf '%s' "$crash" | head -3)"
r8="ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|UnsatisfiedLinkError|AbstractMethodError"
r8="$r8|IncompatibleClassChangeError|VerifyError|ExceptionInInitializerError|Resources\\\$NotFoundException"
r8="$r8|com\.sun\.jna|uniffi|org\.nostrdevkit|SerializationException|Cannot find implementation for"
broken="$(grep -E "$r8" "$out/logcat.txt" || true)"
[ -z "$broken" ] && ok "no R8 / JNI / JNA / UniFFI / serialization / Room errors in the app's logcat" ||
  bad "logcat shows shrinker breakage: $(printf '%s' "$broken" | head -5)"

echo "logs, dumps, screenshots: $out"
[ "$fail" = 0 ] && echo "release smoke: PASS" || echo "release smoke: FAIL"
exit $fail
