#!/bin/sh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Guard rails for experimenting with the SystemUI hook (no root needed, all reversible):
#
#   tools/hook-safety.sh arm      before loading/changing the hook
#   tools/hook-safety.sh disarm   when done: restore Android's defaults
#   tools/hook-safety.sh kill     the hook becomes a no-op on the next SystemUI start (LSPosed untouched)
#   tools/hook-safety.sh unkill
#
# Why: SystemUI is exempt from the per-package cap in Android's crash recovery, so 5 SystemUI crashes in 60 s
# escalate to a warm reboot and then to the recovery screen offering "Factory data reset" (choose "Try again").
# Raising the failure threshold keeps a buggy hook from getting there. Last resort, also without root:
#   adb uninstall dev.spike.glucostatus          (LSPosed stops loading a module that isn't installed)
# For Sukrara do NOT uninstall (it holds the user's identity and data): use `kill` or disable the module in LSPosed.
set -eu
case "${1:-}" in
  arm)
    adb shell device_config set_sync_disabled_for_tests persistent
    adb shell device_config put rollback watchdog_trigger_failure_count 100000
    echo "armed: crash-recovery threshold raised" ;;
  disarm)
    adb shell device_config delete rollback watchdog_trigger_failure_count
    adb shell device_config set_sync_disabled_for_tests none
    echo "disarmed: defaults restored" ;;
  # Kill-switch keys: GlucoStatus (this spike) and Sukrara (spikes/showcase-android) — both, so it works for either.
  kill)   adb shell settings put global glucostatus_hook_disabled 1; adb shell settings put global sukrara_hook_disabled 1 ;;
  unkill) adb shell settings delete global glucostatus_hook_disabled; adb shell settings delete global sukrara_hook_disabled ;;
  *) sed -n '5,13p' "$0"; exit 1 ;;
esac
