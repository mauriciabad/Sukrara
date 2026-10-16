# Power lab — does root help battery and performance? (investigation 11, in progress)

Unattended, unplugged A/B battery runs on the phone, measured with the Pixel's on-device power rails (ODPM, read
through Perfetto — no root needed for measuring) plus the battery charge counter and per-process CPU time.

```
android/        PowerLab (dev.spike.powerlab): runs a plan of windows on the phone itself, with root
                (apply scenario → settle → snapshot → measure → snapshot → restore), exact alarms in deep Doze
overnight.sh    connect over wireless adb · 3-min dry run · start (sized to end by a given time) · status · pull · analyze
usb-run.sh      rootless variant driven from the Mac over USB (phone plugged in, `dumpsys battery unplug` simulated)
analyze.py      energy per window per rail, battery mA, CPU s/h per app, each scenario vs neighbouring baselines
```

Plan file on the phone: `/sdcard/Android/data/dev.spike.powerlab/files/plan.txt`, one window per line:
`name | apply (root shell) | restore (root shell)`. Default plan: `android/app/src/main/res/raw/default_plan`.

## Status

Not run yet: planned overnight on the Sukrara showcase, rootless over USB. **Start with [HANDOFF.md](HANDOFF.md).**

## What the app under test should offer (for whoever builds the showcase app)

PowerLab switches features between windows from a root shell, so the app needs **adb-controllable feature toggles**
— e.g. a receiver protected by `android.permission.DUMP` (only the shell/root can send it), as GlucoStatus' and
GlucoBridge's `DebugReceiver` do. Ideally each of these can be switched independently, without restarting the
process, and the current state can be read back (`resultData`):

| Toggle | Why it's measured |
|---|---|
| each remote upload on/off (Nostr, LibreView, …) **and its interval** (every reading vs batched every N min) | uploads on mobile data are expected to be the biggest cost (radio tail per upload) |
| Mac BLE link (GATT server / advertising) on/off | Bluetooth share of the WLAN_BT rail |
| status-bar surfaces on/off: Live Update chip, dynamic icon, system icons, hook | notification/SystemUI cost per minute |
| "don't refresh the notification while the screen is off" on/off | candidate optimisation |
| sensor link connection priority: default / LOW_POWER / custom interval (hidden `BluetoothGatt.requestLeConnectionUpdate`) | the one Bluetooth lever; only if the app reads the sensor itself |
| any local server (xDrip/Garmin HTTP) on/off | idle cost of listening sockets / wakeups |

Also helpful: a single process (or stable process names) for CPU accounting, no partial wakelocks beyond the work
itself, and a release build (R8) for the measured runs.

Sensor-link windows take the sensor away from Juggluco (and its alarms) unless the showcase app *is* the sensor
reader — those only run with the user's explicit OK.
