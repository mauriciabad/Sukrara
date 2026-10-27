#!/bin/sh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Drive PowerLab (android/) for an unattended, unplugged run over wireless adb:
#   ./overnight.sh connect            find the phone's wireless-debugging port via mDNS and connect
#   ./overnight.sh test               3-min dry run (2 windows × 1 min) with root, then show the result
#   ./overnight.sh start [HH:MM]      start the default plan, measure windows sized to end by HH:MM (default 08:00)
#   ./overnight.sh status | pull | analyze [run-dir]
set -eu
ANDROID_SERIAL=${ANDROID_SERIAL:-}
here=$(cd "$(dirname "$0")" && pwd)
files=/sdcard/Android/data/dev.spike.powerlab/files
py=${PYTHON:-python3}
# Other sessions run emulators: always target the phone. `connect` remembers its wireless serial.
[ -z "${ANDROID_SERIAL:-}" ] && [ -f "$here/.serial" ] && ANDROID_SERIAL=$(cat "$here/.serial")
export ANDROID_SERIAL
lab() { adb shell am broadcast -n dev.spike.powerlab/.ControlReceiver "$@" | sed -n 's/.*data="\(.*\)"/\1/p'; }

case "${1:-}" in
  connect)
    svc=$(adb mdns services | awk '/_adb-tls-connect/ {print $NF; exit}')
    [ -n "$svc" ] || { echo "no wireless-debugging service found (enable it on the phone, same Wi-Fi)"; exit 1; }
    adb connect "$svc" && echo "$svc" > "$here/.serial" && echo "serial $svc saved" ;;
  test)
    adb shell "cp $files/plan.txt $files/plan.full.txt 2>/dev/null; printf 'base|\nno-nostr|am force-stop dev.spike.cgmnostr|am start-foreground-service -n dev.spike.cgmnostr/.PublisherService\n' > $files/plan.txt"
    lab --es cmd start --ei settle 0 --ei measure 1 --ei delay 5
    sleep 200
    lab --es cmd status
    adb shell "cat $files/log.txt | tail -12; mv $files/plan.full.txt $files/plan.txt 2>/dev/null || rm $files/plan.txt" ;;
  start)
    until=${2:-08:00}
    windows=$(adb shell "cat $files/plan.txt 2>/dev/null" | grep -cvE '^\s*(#|$)' || true)
    [ "$windows" -gt 0 ] || windows=13
    now=$(date +%s); end=$(date -j -f %H:%M "$until" +%s); [ "$end" -gt "$now" ] || end=$((end + 86400))
    measure=$(( (end - now) / 60 / windows - 4 ))   # 3 min settle + ~1 min of switching per window
    [ "$measure" -ge 10 ] || { echo "not enough time: $measure min per window"; exit 1; }
    echo "$windows windows × (3 + $measure) min, ends ≈ $until"
    lab --es cmd start --ei settle 3 --ei measure "$measure" --ei delay 120 ;;
  status) lab --es cmd status; adb shell "tail -5 $files/log.txt" ;;
  pull)   mkdir -p "$here/runs"; adb pull "$files/." "$here/runs/" >/dev/null && ls "$here/runs" ;;
  analyze) "$py" -I "$here/analyze.py" "${2:-$(ls -d "$here"/runs/run-* | tail -1)}" ;;
  *) sed -n '5,10p' "$0"; exit 1 ;;
esac
