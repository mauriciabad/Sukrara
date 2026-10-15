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

Foreground service that publishes glucose from a BLE GATT server for the Mac to read (protocol in `Bridge.kt`).
Two product flavors differ only in where the glucose comes from:

| Flavor | Source | Report |
|---|---|---|
| `juggluco` | Juggluco's web server on `127.0.0.1:17580` (works with "Local only" on) | [01](../docs/01-connectivity.md) |
| `direct` | the Libre 3 sensor itself, over BLE | [02](../docs/02-direct-sensor.md) |

```sh
cd glucobridge-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home PATH="$HOME/.local/bin:$PATH"
./gradlew assembleJugglucoDebug      # or assembleDirectDebug
android install --apks=app/build/outputs/apk/juggluco/debug/app-juggluco-debug.apk --install-options=-g
```

The `direct` flavor additionally needs:
- a **Juggluco checkout** at `third_party/Juggluco` — it compiles Juggluco's portable Libre 3 security core (GPLv3)
  through a small JNI layer: `git clone https://github.com/j-kaltes/Juggluco third_party/Juggluco`
- the **NDK and CMake**: `android sdk install ndk/28.2.13676358 cmake/4.1.2`
- this sensor's **pairing data**: `./tools/import-pairing.sh` (one-time, needs root on the phone)

Without the Juggluco checkout the native build is skipped and only `assembleJugglucoDebug` works.

## `ble-scan/` — BLE advertisement scanner (macOS, Swift)

```sh
cd ble-scan && swiftc -O main.swift -o blescan && ./blescan 20 [--all]
```
Lists nearby BLE advertisers and flags anything that looks like an Abbott/Libre device.

## Not published

The Mac-side direct sensor experiment (`libre3-ble`, investigation 01) is kept out of this public repo; its results
are in [`docs/01-connectivity.md`](../docs/01-connectivity.md#1-can-macos-read-the-libre-3-directly). The phone-side
equivalent *is* published, as the `direct` flavor above.
