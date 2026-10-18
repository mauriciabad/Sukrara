# Spikes

Throwaway proofs of concept. Findings live in [`../docs`](../docs); this file is just how to build and run them.

## `menubar/` — Sukrara for Mac (formerly GlucoseBar; macOS, Swift)

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
./bundle.sh                              # → build/Sukrara.app (app.mauri.sukrara.poc; Apple identity + widgets if any, else ad hoc)
./dmg.sh                                 # → build/Sukrara-<version>.dmg, the drag-to-Applications installer
swift run GlucoseBar -alerts-selftest    # glucose alert rules (spikes/alerts/SPEC.md) over a scripted series
open build/Sukrara.app --args -alert-test very_low     # one test notification with its sound (-alert-feed "200,1")
```
Glucose alerts: menu → **Settings…** → Alerts (`alertSettings` default = the SPEC §3 JSON); by default the Mac
mirrors the wearer's `alert` events and checks "no data" itself. Details: [FOLLOWER.md](menubar/FOLLOWER.md).
The Swift target is still called `GlucoseBar` (hence `swift run GlucoseBar`); the bundle is Sukrara. Options are
`UserDefaults`, so for the `.app` use `defaults write app.mauri.sukrara.poc <key> <value>` (the first launch copies the
old `dev.spike.GlucoseBar` domain and follower Keychain item, see `Migration.swift`). Log: `~/Library/Logs/Sukrara.log`.

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
(cd menubar && swift run GlucoseBar -treatmentsselftest)
```
Configure (all off by default):
- **phone**: GlucoBridge → LibreView → email + password, then the switches; or over adb (shell-only receiver):
  `adb shell am broadcast -n dev.spike.glucobridge/.DebugReceiver -a dev.spike.glucobridge.CONFIG --ez upload true`
- **Mac**: no LibreView upload any more (removed in v0.10.9 with the legacy Mac link): Mac entries reach the phone
  over v2 and the phone uploads them.
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

### Protocol v2 — sharing and keys (investigation 06)

Spec: [`nostr/PROTOCOL-v2.md`](nostr/PROTOCOL-v2.md). Python reference + end-to-end tests on public relays:

```sh
cd nostr
uv run v2/test_cgm2.py                      # offline protocol checks
uv run v2/scenarios.py --every 10           # share, deny, forged key, revoke, phone change, missed rotation (≈ 2.5 min)
uv run v2/cli.py owner  --dir secrets/v2-owner --every 30        # long-running owner (writes invite.txt)
uv run v2/cli.py follow --dir secrets/v2-follower --link '<cgmshare:join?…>'
```

`nostr/android-app/` — **CGM Share** (`dev.spike.cgmshare`, Kotlin): one app, "I wear the sensor" / "I follow
someone"; QR/link invites with a match code, followers list + remove, password-manager backup/restore.

```sh
cd nostr/android-app
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home PATH="$HOME/.local/bin:$PATH"
./gradlew assembleRelease && android install --apks=app/build/outputs/apk/release/app-release.apk --install-options=-g
```
All builds are signed with `~/.android/debug.keystore` on purpose: Proton Pass only returns a saved key to an app
signed with the same certificate.

### Identity — profiles, short invites, Nostr login (investigation 14)

Spec: [`nostr/PROTOCOL-identity.md`](nostr/PROTOCOL-identity.md). Python reference + checks (throwaway keys only;
the live check cleans up after itself with Blossom DELETE + NIP-62):

```sh
cd nostr
uv run identity/test_identity.py            # offline checks, writes identity/vectors.json (Kotlin parity fixture)
uv run identity/live_check.py               # relays + Blossom: invite by link, kind 0, uploads, deletion (≈ 5–8 min)
```

`nostr/identity-android/` — **Identity PoC** (`dev.spike.nostrid`, Kotlin): profile editor (generated avatar by
default, Photo Picker, public notice), short invites + join + Allow, hidden "Use an existing Nostr account"
(nsec / ncryptsec / words → derived account; NIP-55 signer such as Amber → sealed root), delete everywhere.

```sh
cd nostr/identity-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest                 # Kotlin ↔ Python vectors
./gradlew assembleDebug && adb -s <emulator> install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s <emulator> shell am broadcast -n dev.spike.nostrid/.DebugReceiver --es cmd create --es name Alex   # or invite,
#   join --es link <link>, poll, allow, deny, derive --es key <THROWAWAY nsec>, delete, reset
adb -s <emulator> shell run-as dev.spike.nostrid cat files/state.json      # redacted state (debug builds)
```

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

## `brand/` — name, icon and style candidates (investigation 09)

`brand.py` (Python stdlib) defines each icon once as vector shapes and writes SVG, Android VectorDrawables, a
gallery and per-platform exports; `render.swift` (AppKit, built automatically) rasterizes and draws the menubar
mock-ups with GlucoseBar's real `TrendArrow`.

```sh
cd brand
python3 -I brand.py gallery              # build/ + open build/gallery/index.html
python3 -I brand.py sheet                # icon contact sheet only (fast design loop) → build/sheet.png
python3 -I brand.py export vane a        # chosen candidate + range kit → assets/vane/ (see its README.md)
python3 -I brand.py exports              # every round-2 candidate → build/exports/ (for looking, not committing)
cd android                               # Brand preview: one launcher entry per candidate (sources generated by brand.py)
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug && adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

## `power/` — battery lab (investigation 11, in progress)

Unattended A/B battery runs with the Pixel's power rails (Perfetto, no root): `usb-run.sh` (rootless, over USB) or
PowerLab (`power/android`, root, unplugged). Plan for Sukrara in `power/plans/sukrara.txt`. Status and how to run:
[`power/HANDOFF.md`](power/HANDOFF.md).

## `alerts/` — glucose alerts, sound packs (investigation 15)

The rules, settings JSON and `alert` event are in [`alerts/SPEC.md`](alerts/SPEC.md); the code lives in the apps
(`showcase-android/…/alerts/`, `menubar/Sources/GlucoseBar/Alerts*.swift`). The sounds are generated:

```sh
cd alerts/sounds
uv run gen.py                                   # → out/<pack>_<kind>_<n>.{wav,ogg,caf,m4a} (~40 s)
cp out/*.ogg out/silence.ogg ../../showcase-android/app/src/main/res/raw/     # Android (bundle.sh takes the .caf)
uv run preview.py site ../../../docs/sounds     # the sound test page on sukrara.mauri.app/sounds
```
Phone tests (adb only): `am broadcast -n app.mauri.sukrara.poc/app.mauri.sukrara.features.DebugReceiver --es alertTest
high` (one alert, also sent to followers), `--es alertFeed '204,-0.5'` (a made-up reading through the rules).
`adb logcat -s Sukrara.Alerts` shows each firing and which sound file SystemUI opened.

## Graph (investigation 16)

The glucose card (value, rings, chart with the time-at-level distribution) is drawn by one renderer:
`showcase-android/…/graph/` on Android and `widgets-macos/Shared/SukraraGraph.swift` on the Mac (the menubar target
links it through a symlink). Picture sheets and tests on the JVM (Robolectric's Android 16 sandbox needs Java 21+):

```sh
cd showcase-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
GALLERY_JAVA_HOME=$(brew --prefix)/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  ./gradlew testDebugUnitTest --tests '*GraphGallery*' --tests '*GlucoseStatsTest*' -Dgallery.out=$PWD/../../docs/img/16
cd ../menubar && swift run GlucoseBar -graphselftest     # the Swift port's checks
```
Phone/emulator: `--es graph '{"defaultHours":6,"ringCount":3}'`, `--es widgetGraph '<id>:{"hours":3,"rings":1}'`,
`--es cmd pinWidget --es kind combined` (asks the launcher to add a widget; the app must be in the foreground).
On the Mac the same settings are `UserDefaults` (`graphHours`, `ringCount`, `ringWindows`, `ringRange`,
`bandVeryLow` … `bandVeryHigh`, `side`) or Settings → Graph. The Mac widgets have no per-widget settings
(App Intents need an Xcode project to register; see docs/16).

## `ble-scan/` — BLE advertisement scanner (macOS, Swift)

```sh
cd ble-scan && swiftc -O main.swift -o blescan && ./blescan 20 [--all]
```
Lists nearby BLE advertisers and flags anything that looks like an Abbott/Libre device.

## `widgets-android/` — Glucose Widgets (Android, Kotlin + Jetpack Glance, investigation 12)

Value (1×1/2×1/2×2), graph and graph + value home-screen widgets, pushed every minute by a small foreground service
([12](../docs/12-android-widgets.md)). Data: Juggluco on localhost, or a synthetic sine for the emulator.
```sh
cd widgets-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
# open the app → "Pin … widget" buttons; drive it from adb:
adb shell am broadcast -n dev.spike.glucowidgets/.DebugReceiver --es cmd scenario --ei phaseMin 40 --ei freezeAgeMin 12
adb shell am start -n dev.spike.glucowidgets/.MainActivity --ez restart true   # apply (FGS start needs the foreground)
adb shell am broadcast -n dev.spike.glucowidgets/.DebugReceiver --es cmd stats   # renders / skipped counters
```

## `notification-android/` — NotifLab: glucose notification templates (Android, Kotlin, investigation 19)

Posts the same synthetic reading as several notifications side by side (custom card, ProgressStyle variants, a Live
Update, BigPictureStyle, MetricStyle) to compare what each template draws ([19](../docs/19-notification-templates.md)).
```sh
cd notification-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.spike.notiflab android.permission.POST_NOTIFICATIONS
# variants: baseline bands bands_c tir timeline live picture picture_c metric (extras: sgv, rate, hours, clear)
adb shell am start -S -n dev.spike.notiflab/.MainActivity --es v live,bands --ei sgv 138 --ef rate 0.7 --ez clear true
adb logcat -s NotifLab
```

## `sensors-android/` — sensor drivers for other brands (Kotlin, investigation 23)

Drivers for sensors Sukrara does not read yet, with **no app and no Bluetooth**: a `SensorDriver` abstraction the
Libre 3 code also fits, and decoders tested against packet traces and test vectors from the open projects
([23](../docs/23-sensors.md)). Ported: Dexcom G7 / ONE+ / Stelo (observer mode: reads along with the official
Dexcom app, writing nothing; its own pairing stops at the Dexcom app certificate it refuses to borrow), Dexcom
G6 / ONE, Accu-Chek SmartGuide (the standard Bluetooth CGM Service) and Aidex X. **Every driver is untested on
hardware** — the user owns none of these sensors.

```sh
cd sensors-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
./gradlew :core:test                 # 36 tests: crypto, checksums, decoders, handshake decisions
./gradlew :core:run                  # the decoder harness over every bundled trace
./gradlew :core:run --args="aidex"   # one driver (g7, g6, smartguide, aidex), or add a trace file path
./gradlew :android:assembleRelease   # the BluetoothGatt transport (compiled, never run against a sensor)
```

Traces and where each byte comes from: `core/src/main/resources/traces/README.md`.

## `widgets-macos/` — WidgetKit widgets for the Mac (investigation 13)

Value / graph / combined widgets (small → extra large), built with the Command Line Tools only. macOS refuses to
load the extension without a Team ID certificate, so the views are also rendered to PNG ([13](../docs/13-macos-widgets.md)).
```sh
cd widgets-macos
./build.sh render                         # every widget family → evidence/*.png (ImageRenderer, no WidgetKit host)
./build.sh install                        # host app + .appex → ~/Applications, lsregister + pluginkit, start host
SIGN_ID=<sha1> ./build.sh install         # same, signed with a real (Team ID) certificate
PROBE_ID=dev.spike.GlucoseWidgets.widget ./build.sh probe   # which data transports the sandboxed widget can read
Diag/chronolog.sh 60                      # what chronod says about the extension
```

## Not published

The Mac-side direct sensor experiment (`libre3-ble`, investigation 01) is kept out of this public repo; its results
are in [`docs/01-connectivity.md`](../docs/01-connectivity.md#1-can-macos-read-the-libre-3-directly). The phone-side
equivalent *is* published, as the `direct` flavor above.
