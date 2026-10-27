#!/bin/sh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Rootless variant of PowerLab, driven from the Mac over USB adb (the phone stays plugged in):
#   caffeinate -i ./usb-run.sh <plan> <settle-min> <measure-min>
# Same plan format (name | apply | restore), but the commands run as the adb *shell* user: force-stop, svc wifi,
# deviceidle, am broadcast to DUMP-protected receivers all work; root-only lines (cpuset…) fail and are logged.
# `dumpsys battery unplug` lets Doze and batterystats behave as if unplugged; the power rails are unaffected by
# charging, the battery charge counter is not (ignore batt_mA for these runs). Results → runs/<run>/ for analyze.py.
set -u
here=$(cd "$(dirname "$0")" && pwd)
export ANDROID_SERIAL=${ANDROID_SERIAL:-62240DLCH0005W}   # other sessions run emulators
plan=$1; settle=$2; measure=$3
dev=/data/local/tmp/powerlab
name=run-$(date +%Y%m%d-%H%M)-usb
run=$dev/$name
mkdir -p "$here/runs"
logf="$here/runs/$name.log"
log() { echo "$(date +%T) $*" | tee -a "$logf"; }
sh_() { adb shell "$1" </dev/null 2>&1 | tr '\n' ' ' | cut -c1-300; }   # </dev/null: don't eat the plan lines

cleanup() {
  log "cleanup: $(sh_ 'dumpsys deviceidle unforce; dumpsys battery reset')"
  adb pull "$run" "$here/runs/" >/dev/null 2>&1 && log "pulled to runs/$name"
}
trap cleanup EXIT INT TERM

adb shell mkdir -p "$run" </dev/null
adb push "$here/android/app/src/main/res/raw/snap.sh" "$dev/snap.sh" >/dev/null
cp "$plan" "$here/runs/$name.plan.txt"
log "start $name: settle $settle + measure $measure min"
sh_ 'input keyevent KEYCODE_SLEEP; dumpsys battery unplug' >/dev/null

i=0
grep -vE '^[[:space:]]*(#|$)' "$plan" | while IFS='|' read -r wname apply restore; do
  i=$((i + 1))
  wname=$(echo "$wname" | xargs)
  label=$(printf '%02d-%s' "$i" "$wname")
  [ -n "$(echo "$apply" | xargs)" ] && log "$label apply: $(sh_ "$apply")"
  log "$label idle: $(sh_ 'dumpsys deviceidle force-idle deep')"
  sleep $((settle * 60))
  log "$label start: $(sh_ "sh $dev/snap.sh $run $label-start && echo ok")"
  sleep $((measure * 60))
  log "$label end: $(sh_ "sh $dev/snap.sh $run $label-end && echo ok")"
  [ -n "$(echo "$restore" | xargs)" ] && log "$label restore: $(sh_ "$restore")"
done
log "plan finished"
