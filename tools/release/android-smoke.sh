#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# The Android release gate, on an emulator only (never a phone):
#   tools/release/android-smoke.sh [--final] [--no-build] [--no-poc] [--keep-emulator] [--wait SECONDS]
# 1. builds the signed release APK (the Sukrara release key; no key → stop, never the debug key) and runs
#    verify-apk.sh on it
# 2. installs the showcase (PoC) debug build next to it, in both orders: both packages installed, both launch,
#    stopping one leaves the other running, each has its own notification channels
# 3. fresh V1 install: launches, renders its first screen, survives being recreated from saved state, stays alive;
#    the monitor's notification says it isn't monitoring yet (no sensor) and is promoted (Live Update chip)
# 4. no alert fires with no sensor, also after the clock jumps 3 h ahead (past every no-data delay)
# 5. logcat of the app's uid has no crash and no R8 / JNI / JNA / UniFFI / serialization / Room breakage
# --final (the release gate at the end): every WARN (a feature not wired yet, the PoC not built) is a FAIL.
# Logs, dumps and screenshots (fresh install, no data) stay in $TMPDIR/sukrara-smoke.<time>/. Exit 0 only when
# nothing FAILed.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/lib.sh"
root="$(repo_root)"

final=0 build=1 poc=1 keep=0 wait_s=180
while [ $# -gt 0 ]; do
  case "$1" in
    --final) final=1 ;;
    --no-build) build=0 ;;
    --no-poc) poc=0 ;;
    --keep-emulator) keep=1 ;;
    --wait) wait_s="${2:?--wait SECONDS}"; shift ;;
    -h | --help) sed -n '5,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option $1 (--help)" >&2; exit 2 ;;
  esac
  shift
done

V1=app.mauri.sukrara
POC=app.mauri.sukrara.poc
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
poc_apk="$root/poc/spikes/showcase-android/app/build/outputs/apk/debug/app-debug.apk"

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
  if [ "$poc" = 1 ]; then
    echo "building the showcase debug APK (log: $out/build-poc.log)"
    rm -f "$poc_apk"   # a failed build must not leave an old APK to test
    (cd "$root/poc/spikes/showcase-android" && ./gradlew :app:assembleDebug --console=plain) > "$out/build-poc.log" 2>&1 ||
      warn "showcase debug build failed ($out/build-poc.log): coexistence not checked"
  fi
fi
"$here/verify-apk.sh" "$apk" "$mapping" || fail=1

wait_emulator

# --- 2. install side by side, both orders ---------------------------------------------------------------------------
install_v1() { adb install "$apk" > "$out/install-v1.log" 2>&1 || die "installing the release APK failed ($out/install-v1.log)"; }
install_poc() { adb install "$poc_apk" > "$out/install-poc.log" 2>&1 || { bad "the showcase doesn't install beside V1 ($out/install-poc.log)"; poc=0; }; }
adb uninstall "$V1" > /dev/null 2>&1 || true
adb uninstall "$POC" > /dev/null 2>&1 || true
if [ "$poc" = 1 ] && [ -f "$poc_apk" ]; then
  install_v1; install_poc                                            # showcase installed after V1
  [ "$poc" = 1 ] && { adb uninstall "$V1" > /dev/null; install_v1; } # V1 installed after the showcase
  [ "$poc" = 1 ] && ok "V1 and the showcase install beside each other in both orders"
else
  [ "$poc" = 1 ] && warn "no showcase APK at $poc_apk: coexistence not checked"
  poc=0
  install_v1
fi
ok "release APK installed (fresh: no data)"
pkgs="$(adb shell pm list packages -U | tr -d '\r')"
uid="$(printf '%s\n' "$pkgs" | sed -n "s/^package:$V1 uid:\([0-9]*\)$/\1/p")"
[ -n "$uid" ] && ok "pm lists $V1 (uid $uid)" || die "pm doesn't list $V1"
if [ "$poc" = 1 ]; then printf '%s\n' "$pkgs" | grep -q "^package:$POC " && ok "pm lists $POC beside it" || bad "pm doesn't list $POC"; fi

# --- 3. launch ------------------------------------------------------------------------------------------------------
pid_of() { adb shell pidof "$1" 2>/dev/null | tr -d '\r'; }
launch_v1() { adb shell am start -W -n "$V1/.MainActivity" > "$out/start-v1.txt" 2>&1 || bad "am start $V1 failed ($out/start-v1.txt)"; sleep 5; }
adb shell pm grant "$V1" android.permission.POST_NOTIFICATIONS 2> /dev/null || true
adb logcat -G 16M > /dev/null 2>&1 || true   # the whole run fits: start-up errors must not wrap away
adb logcat -c
launch_v1
[ -n "$(pid_of "$V1")" ] && ok "$V1 is running after launch" || bad "$V1 isn't running 5 s after launch"
adb exec-out screencap -p > "$out/v1-first-screen.png"
adb shell uiautomator dump /sdcard/smoke-ui.xml > /dev/null 2>&1 && adb pull /sdcard/smoke-ui.xml "$out/v1-first-screen.xml" > /dev/null 2>&1 || true
if grep -q "package=\"$V1\"" "$out/v1-first-screen.xml" 2> /dev/null; then
  ok "first screen rendered: $(grep -o 'text="[^"]\{1,\}"' "$out/v1-first-screen.xml" | head -3 | tr '\n' ' ')"
else bad "no view of $V1 on screen ($out/v1-first-screen.png)"; fi

# Saved state (navigation + kotlinx.serialization keys) after the activity is destroyed in the background.
adb shell settings put global always_finish_activities 1
adb shell input keyevent KEYCODE_HOME; sleep 2
launch_v1
adb shell settings put global always_finish_activities 0
adb shell uiautomator dump /sdcard/smoke-ui.xml > /dev/null 2>&1 && adb pull /sdcard/smoke-ui.xml "$out/v1-restored.xml" > /dev/null 2>&1 || true
if [ -n "$(pid_of "$V1")" ] && grep -q "package=\"$V1\"" "$out/v1-restored.xml" 2> /dev/null; then
  ok "$V1 renders again after its activity was destroyed (saved state restored)"
else bad "$V1 didn't come back after its activity was destroyed ($out/v1-restored.xml)"; fi

if [ "$poc" = 1 ]; then
  # The showcase's launcher activity (poc/ is frozen). Its first start leaves a permission dialog on top of its task,
  # which swallows a plain relaunch: start it in a fresh task every time.
  launch_poc() { adb shell am start -W -S --activity-clear-task -n "$POC/app.mauri.sukrara.ui.MainActivity" > /dev/null 2>&1 || true; sleep 3; }
  adb shell pm grant "$POC" android.permission.POST_NOTIFICATIONS 2> /dev/null || true
  launch_poc
  [ -n "$(pid_of "$POC")" ] && ok "$POC launches beside it" || bad "$POC doesn't run"
  adb shell am force-stop "$POC"; sleep 2
  [ -n "$(pid_of "$V1")" ] && ok "stopping $POC leaves $V1 running" || bad "$V1 died when $POC was stopped"
  launch_poc
  adb shell am force-stop "$V1"; sleep 2
  [ -n "$(pid_of "$POC")" ] && ok "stopping $V1 leaves $POC running" || bad "$POC died when $V1 was stopped"
  adb shell am force-stop "$POC"
  launch_v1
fi

# --- 4. the window, then 3 h later ----------------------------------------------------------------------------------
echo "watching $V1 for ${wait_s}s (no sensor: no alert may fire)"
sleep "$wait_s"
adb exec-out screencap -p > "$out/v1-after-window.png"
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

[ -n "$(pid_of "$V1")" ] && ok "$V1 still running after the window" || bad "$V1 died in the window (logcat: $out/logcat.txt)"

# Notification records of a package: the "NotificationRecord(… pkg=<pkg> …" blocks of a dump.
records() { awk -v p="pkg=$1 " 'index($0,"NotificationRecord(")&&index($0,p){on=1;print;next} index($0,"NotificationRecord("){on=0} on' "$2"; }
records "$V1" "$out/notifications.txt" > "$out/v1-records.txt"
# The record that carries "Not monitoring" (one block per NotificationRecord).
awk 'index($0,"NotificationRecord("){if (hit) printf "%s", rec; rec=""; hit=0} {rec=rec $0 "\n"} /Not monitoring/{hit=1} END{if (hit) printf "%s", rec}' \
  "$out/v1-records.txt" > "$out/v1-glucose-record.txt"
if [ -s "$out/v1-glucose-record.txt" ]; then
  ok "the glucose notification says it isn't monitoring yet"
  # The Live Update chip (P0): API 36+ dumps flags by name (…|PROMOTED_ONGOING), older ones as hex (0x40000).
  flags="$(grep -E '^ +flags=' "$out/v1-glucose-record.txt" | head -1 | sed 's/.*flags=//')"
  promoted=0
  case "$flags" in *PROMOTED_ONGOING*) promoted=1 ;; 0x*) [ $((flags & 0x40000)) -ne 0 ] && promoted=1 ;; esac
  if [ "$promoted" = 1 ]; then ok "it is promoted: Live Update chip (flags $flags)"
  else bad "the glucose notification isn't promoted (flags ${flags:-?}): no Live Update chip"; fi
else
  warn "no 'Not monitoring' glucose notification (the monitor isn't started on this branch?) — $out/v1-records.txt"
fi
no_alert() {
  if records "$V1" "$1" | grep -Eq "channel=alert|fullscreenIntent=PendingIntent"; then
    bad "an alert fired with no sensor ($2)"
  else ok "no alert notification with no sensor ($2)"; fi
}
no_alert "$out/notifications.txt" "after ${wait_s}s"
[ "$clock_jumped" = 1 ] && no_alert "$out/notifications-3h.txt" "3 h later"

# Channels per package (AppSettings blocks): each app keeps its own.
channels() { awk -v p="AppSettings: $1 " 'index($0,"AppSettings: "){on=index($0,p)>0} on' "$out/notifications.txt" | grep -o "mId='[^']*'" | sort -u; }
channels "$V1" > "$out/v1-channels.txt"
[ -s "$out/v1-channels.txt" ] && ok "$V1 channels: $(tr '\n' ' ' < "$out/v1-channels.txt")" || warn "$V1 has no notification channels yet"
if [ "$poc" = 1 ]; then
  channels "$POC" > "$out/poc-channels.txt"
  [ -s "$out/poc-channels.txt" ] && ok "$POC keeps its own channels ($(wc -l < "$out/poc-channels.txt" | tr -d ' '))" ||
    warn "$POC shows no channels in the dump"
fi

# --- 5. logcat ------------------------------------------------------------------------------------------------------
crash="$(grep -h "FATAL EXCEPTION" "$out/logcat.txt" || true; grep -h "Process: $V1," "$out/logcat-crash.txt" || true)"
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
