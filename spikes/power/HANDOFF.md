# Handoff — investigation 11: does root help battery and performance?

*Written 2026-10-16 by the session that started it (it was numbered 06 before the renumbering). For a fresh agent.*

## Status

**Research done, tools built and tested, the measurement has not run yet.** It is planned as an overnight run on
**Sukrara** (the showcase, `app.mauri.sukrara.poc`) while the user sleeps, with the phone on **USB** (the user can't
unplug it). Nothing is in `docs/11` yet — write `docs/11-battery-root.md` from the run's results + the research below.

## What's already known (research, no device run yet)

Answer to "can root help?": **for battery, almost nothing that adb or normal APIs can't do; root mostly buys
reliability, and that has costs.**

| Lever | Root needed? | Expected | Notes |
|---|---|---|---|
| Batch uploads (Nostr, LibreView) instead of one per reading | no | **largest** on mobile data (cellular radio "tail" per upload, est. several %/day at 1/min); small on Wi-Fi | Sukrara: `--ei nostrInterval N` |
| Don't run two CGM apps in parallel (GlucoDataHandler, Juggluco + a reader) | no | removes duplicate work; GDH had used **~17 min CPU/day** (5,575 s in 5.3 days of uptime) | measured from /proc, 2026-10-15 |
| Skip notification refreshes while the screen is off | no | probably small | Sukrara: `--ez screenOffSkip true` |
| Sensor BLE connection interval | no (`requestConnectionPriority`, or hidden `BluetoothGatt.requestLeConnectionUpdate` with only BLUETOOTH_CONNECT) | small, ~0.05–0.4 %/day; the Libre 3 may override (phone accepts peripheral requests) | root `resetprop bluetooth.gatt.low_priority_*` is redundant. Needs direct mode = takes the sensor from Juggluco and its alarms → **only with the user's explicit OK** |
| Mac link parameters (phone is peripheral) | no | small–moderate if macOS keeps 15–30 ms | macOS rules: interval multiple of 15 ms, etc. |
| Battery-optimisation exemption / Companion Device association | no | reliability, ~0 battery | `dumpsys deviceidle whitelist +pkg`, `cmd companiondevice associate` |
| Persistent system app (KernelSU module) | **yes** | **no battery gain**; process priority −800, auto-restart | bootloop risk if privileged perms aren't allowlisted (`/system/app` avoids it); can't be updated with `adb install` on a user build. **User chose to skip it.** |
| Pin CGM apps to little cores (cpuset/uclamp) | yes | placebo (ms of CPU per minute) | in PowerLab's root plan only |
| Kernel wakelock blockers | yes | only if data shows a bad wakelock | — |

Also noted: Sukrara holds a **10 s partial wakelock per reading** (docs/10) — worth checking in the results.

**Measuring needs no root:** the Pixel's on-device power monitor (ODPM, 32 rails incl. `wifi.bt`, `modem`,
`radio.frontend`, CPU little/mid/big, DDR, display) is readable through Perfetto as the adb shell user, and the
rails are cumulative energy counters, so a 3 s snapshot at the start and end of a window gives the window's energy
with no sampling in between.

## Tools (this directory)

| File | What |
|---|---|
| `usb-run.sh` | **use this one** (rootless, Mac-driven over USB): `caffeinate -i ./usb-run.sh plans/sukrara.txt 3 22` → per window: apply → force deep Doze → settle 3 min → snapshot → measure 22 min → snapshot → restore. Results pulled to `runs/run-…-usb/` + a log |
| `plans/sukrara.txt` | 18 windows (~7.5 h): baselines interleaved with nostr batch 15 min / nostr off / BLE off / chip+icon off / screen-off skip / GDH stopped, then Wi-Fi off: cell base, cell nostr batch, cell nostr off |
| `analyze.py` | `python analyze.py runs/<run>` (needs `pip install perfetto`): per-window mW per rail group, CPU s/h per app (Sukrara, Juggluco, GDH, SystemUI, system_server, Bluetooth), each scenario vs the neighbouring baselines, baseline spread = noise floor |
| `android/` (PowerLab, `dev.spike.powerlab`, installed) | on-device, root variant for unplugged runs over wireless adb; **PowerLab has no root grant yet**; `overnight.sh` drives it |
| `README.md` | what the app under test must expose (Sukrara does: DUMP-protected `DebugReceiver`, live switches) |

Python with perfetto: `python3 -m venv /tmp/venv && /tmp/venv/bin/pip install perfetto`, then
`PYTHON=/tmp/venv/bin/python`. The snapshot script is `android/app/src/main/res/raw/snap.sh` (usb-run pushes it).

## How to run it (tonight)

1. Agree the time with the user ("tell me when you go to bed"). Tell the other sessions the phone is reserved
   until ~08:00 (no adb, installs, wakes); they must pin `-s` (emulators are attached).
2. Sanity run (3 min): a 2-line plan (`base|` and one switch) with `./usb-run.sh <plan> 0 1`, check
   `python analyze.py runs/<run>` prints numbers and the Sukrara switch was restored (DebugReceiver with no extras
   prints the state JSON).
3. `caffeinate -i ./usb-run.sh plans/sukrara.txt 3 22` in the background; check the log occasionally (don't run
   other adb commands during windows).
4. Morning: analyze, write `docs/11`, add a README row, commit + push (rebase on origin/main first — the shared
   checkout often has other sessions' uncommitted work; use a separate worktree).

## Caveats to state in docs/11

- On USB: **battery %/charge is not valid** (charging); Doze is **forced** (`deviceidle force-idle deep`) rather than
  entered naturally; `batterystats` per-app power is a model anyway (no BT/Wi-Fi energy consumer on this phone).
- `wifi.bt` is one rail for Wi-Fi + Bluetooth: Bluetooth deltas are visible only as differences between windows.
- The user's own apps (Juggluco, GDH, …) run in every window — differences, not absolutes, are the result.
- Not measured: sensor-link interval (needs direct mode + consent), root levers (PowerLab lacks root; expected
  placebo), the persistent-app module (skipped by the user).
