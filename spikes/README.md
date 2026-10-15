# Spikes

Throwaway proofs of concept. Findings live in [`../docs`](../docs); this file is just how to build and run them.

## `menubar/` — GlucoseBar (macOS, Swift)

Menubar item: glucose number + continuous-angle trend arrow, colour by range, stale strikethrough, dropdown with a 3 h
sparkline. Two transports run at once and are merged:
- **Wi-Fi** → Juggluco's web server (auto-discovered on the local subnet, history + `/x/live` SSE),
- **Bluetooth LE** → GlucoBridge on the phone.

```sh
cd menubar
swift run GlucoseBar                     # Wi-Fi auto-discovery + BLE
swift run GlucoseBar -lan NO             # BLE only          (-ble NO: Wi-Fi only)
swift run GlucoseBar -url http://<phone-ip>:17580 -secret '…' -unit mmol
swift run GlucoseBar -preview /tmp/arrows.png    # render the arrow strip for all rates
swift run GlucoseBar -snapshot /tmp/item.png     # dump the live menubar button + sparkline to PNGs
./bundle.sh                              # → build/GlucoseBar.app (ad-hoc signed, menubar-only)
```
Options are `UserDefaults`, so for the `.app` use `defaults write dev.spike.GlucoseBar <key> <value>`.

## `glucobridge-android/` — GlucoBridge (Android, Kotlin)

Foreground service: follows Juggluco on `127.0.0.1:17580` (works with "Local only" on) and republishes each reading
from a BLE GATT server (protocol in [`docs/01-connectivity.md`](../docs/01-connectivity.md#glucobridge-spikesglucobridge-android-kotlin)
and `Bridge.kt`). Project created with `android create empty-activity`.

```sh
cd glucobridge-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home PATH="$HOME/.local/bin:$PATH"
./gradlew assembleDebug
android run --apks=app/build/outputs/apk/debug/app-debug.apk --install-options=-g   # -g grants BT/notification permissions
```

## `ble-scan/` — BLE advertisement scanner (macOS, Swift)

```sh
cd ble-scan && swiftc -O main.swift -o blescan && ./blescan 20 [--all]
```
Lists nearby BLE advertisers and flags anything that looks like an Abbott/Libre device.

## Not published

The direct Mac ↔ sensor experiment (`libre3-ble`) is kept out of this public repo; its results are in
[`docs/01-connectivity.md`](../docs/01-connectivity.md#1-can-macos-read-the-libre-3-directly).
