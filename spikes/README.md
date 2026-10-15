# Spikes

Throwaway proofs of concept. Findings live in [`../docs`](../docs); this file is just how to build and run them.

## `menubar/` — GlucoseBar (macOS, Swift)

Menubar item: glucose number + continuous-angle trend arrow, colour by range, stale strikethrough, dropdown with a 3 h
sparkline. Transports run at once and are merged:
- **Wi-Fi** → Juggluco's web server (auto-discovered on the local subnet, history + `/x/live` SSE),
- **Bluetooth LE** → GlucoBridge on the phone,
- **Nostr** → an encrypted CGM stream on public relays ([03](../docs/03-nostr.md)), when a follower link is configured.

```sh
cd menubar
swift run GlucoseBar                     # Wi-Fi auto-discovery + BLE
swift run GlucoseBar -lan NO             # BLE only          (-ble NO: Wi-Fi only)
swift run GlucoseBar -lan NO -ble NO -nostr-file ../nostr/secrets/follower-link.txt   # Nostr only
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

The `direct` flavor also serves the **xDrip+/Juggluco web service on `127.0.0.1:17580`** for Garmin watch apps
([08](../docs/08-garmin.md)); force-stop Juggluco first, since it holds that port. `adb logcat -s GlucoBridge.Xdrip`
shows one line per watch request.

The `direct` flavor additionally needs:
- a **Juggluco checkout** at `third_party/Juggluco` — it compiles Juggluco's portable Libre 3 security core (GPLv3)
  through a small JNI layer: `git clone https://github.com/j-kaltes/Juggluco third_party/Juggluco`
- the **NDK and CMake**: `android sdk install ndk/28.2.13676358 cmake/4.1.2`
- this sensor's **pairing data**: `./tools/import-pairing.sh` (one-time, needs root on the phone)

Without the Juggluco checkout the native build is skipped and only `assembleJugglucoDebug` works.

## `libreview/` — treatments + LibreView upload (investigation 04)

Both apps keep a treatment log (insulin / carbs / notes), sync it over a 4th GlucoBridge characteristic, and upload
it (plus, in the `direct` flavor, sensor glucose) to LibreView. Wire formats: [`libreview/SPEC.md`](libreview/SPEC.md).

```sh
python3 -I libreview/lv.py fixture        # regenerate the golden payload both apps are tested against
(cd glucobridge-android && ./gradlew testJugglucoDebugUnitTest)
(cd menubar && swift run GlucoseBar -lvfixture ../libreview/fixture.json && swift run GlucoseBar -treatmentsselftest)
```
Configure (all off by default):
- **phone**: GlucoBridge → LibreView → email + password, then the switches; or over adb (shell-only receiver):
  `adb shell am broadcast -n dev.spike.glucobridge/.DebugReceiver -a dev.spike.glucobridge.CONFIG --ez upload true`
- **Mac**: `security add-generic-password -s dev.spike.libreview -a <email> -w`, then
  `defaults write dev.spike.GlucoseBar lvEmail <email>` and `… lvUpload -bool YES` (domain `GlucoseBar` when run via
  `swift run`). The Mac uses the DeviceId the phone sends over BLE.
- The account accepts one active device: a new one needs "take over" (`SetDevice:true`), which logs Juggluco's
  LibreView upload out — switch Juggluco's *Send to LibreView* off first, and back on when done.

## `nostr/` — CGM over Nostr (investigation 03)

Protocol in [`nostr/PROTOCOL.md`](nostr/PROTOCOL.md). Python tooling (needs [`uv`](https://docs.astral.sh/uv/)):

```sh
cd nostr
uv run relay_survey.py --expiry-wait                      # what public relays accept
uv run keys.py new --password '…'                         # test identity → secrets/ (gitignored)
uv run publisher.py --synthetic                           # desktop test uploader (or --juggluco <url>)
uv run follower.py --link-file secrets/follower-link-desktop.txt --port 1337   # SQLite + Nightscout v1 API
uv run api_check.py http://127.0.0.1:1337                  # replay real clients' request shapes
uv run ha_check.py                                         # stock Home Assistant (Docker) + Nightscout integration
```

`nostr/android-publisher/` — **CGM Nostr** (`dev.spike.cgmnostr`, Kotlin): reads Juggluco on localhost and publishes
the stream; shows the follower link as a QR code.

```sh
cd nostr/android-publisher
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home PATH="$HOME/.local/bin:$PATH"
./gradlew assembleRelease && android install --apks=app/build/outputs/apk/release/app-release.apk --install-options=-g
```
Followers need the link shown in the app (text + QR). With a debug build (`assembleDebug`) you can also pull it:
`adb shell run-as dev.spike.cgmnostr cat files/follower-link.txt > ../secrets/follower-link.txt`.
## `statusbar-android/` — GlucoStatus (Android, Kotlin)

Glucose in the phone's status bar, every approach from [05](../docs/05-android-status-bar.md) switchable in one app:
Live Update chip, number as notification icon, system icons via a root/adb helper (`SlotDaemon`), and an LSPosed
module (libxposed API 102, scope System UI) that draws next to the clock. Data: Juggluco on localhost, or a demo sine.

```sh
cd statusbar-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home PATH="$HOME/.local/bin:$PATH"
./gradlew assembleDebug && adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell cmd notification allow_listener dev.spike.glucostatus/dev.spike.glucostatus.ChipYieldListener
# drive it from adb (Config field names as boolean extras; injected values are pinned for 2 min)
adb shell am broadcast -n dev.spike.glucostatus/.DebugReceiver --ez demo true --ez slot true --ez hook true
adb shell am broadcast -n dev.spike.glucostatus/.DebugReceiver --ei mgdl 187 --ef rate -1.2 --es hookPosition slot:wifi
adb shell am broadcast -n dev.spike.glucostatus/.DebugReceiver --ez restartSystemUi true   # root: reload the hook
tools/hook-safety.sh arm|disarm|kill|unkill      # guard rails while experimenting with the hook
swift tools/gen-slot-drawables.swift GoogleSansFlex.ttf app/src/main/res/drawable   # regenerate sb_* icons
```
3a needs root granted to GlucoStatus in KernelSU (or run `SlotDaemon` over adb); 3b needs the module enabled in LSPosed.

## `ble-scan/` — BLE advertisement scanner (macOS, Swift)

```sh
cd ble-scan && swiftc -O main.swift -o blescan && ./blescan 20 [--all]
```
Lists nearby BLE advertisers and flags anything that looks like an Abbott/Libre device.

## Not published

The Mac-side direct sensor experiment (`libre3-ble`, investigation 01) is kept out of this public repo; its results
are in [`docs/01-connectivity.md`](../docs/01-connectivity.md#1-can-macos-read-the-libre-3-directly). The phone-side
equivalent *is* published, as the `direct` flavor above.
