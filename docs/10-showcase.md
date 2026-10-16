# 10 — Sukrara: the showcase app

*2026-10-16 · investigation report · Pixel 10 Pro (Android 17), Juggluco + Libre 3 Plus, macOS GlucoseBar*

## Goal

Put every feature proven in investigations 01–09 into **one Android app** that the user runs for a few days, to judge
whether it's useful, what's buggy and how it feels day to day. It's still a throwaway alpha. The final app gets
rebuilt from scratch.

## Result

**Sukrara `0.1-inv10` (release, R8, signed with the debug key) is installed and running on the phone as the
wearer, with Juggluco as the source.** Checked on the phone at 09:05–09:17:

| Feature | Default | Verified on the phone |
|---|---|---|
| Glucose from Juggluco (`127.0.0.1:17580`, history + `/x/live` SSE) | **ON** | `live (Juggluco SSE)`, 1600-entry backfill at start |
| Status bar: Live Update chip + number as the notification icon | **ON** | notification flags `PROMOTED_ONGOING`, `MetricStyle`, bitmap icon 96×96 ([dump](../spikes/showcase-android/evidence/phone-statusbar-notification.txt)) |
| Sharing over Nostr (v2: live + hourly chunks, roster, owner backup) | **ON**, every reading | 25 chunks + live events published. **GlucoseBar paired over Nostr** (match code 4096 on both sides, approved) and shows live values ~1 s after the phone |
| Bluetooth followers (v2 GATT `7c6a0001-…`) | **ON** | GlucoseBar (v2) connected over BLE, got `INFO`, the backlog chunk and live readings `via ble:Tartaruga` |
| Mac link for today's GlucoseBar (legacy GATT `6c1f0001-…`, READING/HISTORY/TREATMENTS) | **ON** | GlucoseBar: `[Bluetooth · GlucoBridge] 105 mg/dL … latency 30 s` then ~0 s; treatment sync started |
| Garmin watch server (xDrip+-compatible, `127.0.0.1:17580`) | **ON** | Port taken by Juggluco, as expected: Juggluco keeps serving the watch. On the emulator (no Juggluco) it listens |
| Treatments (insulin / carbs / notes) | available | emulator: logged "2.5 U rapid · breakfast" |
| LibreView upload | **OFF** (setup screen) | never enabled, nothing posted |
| Direct Libre 3 source (inv. 02) | **OFF**, opt-in with a warning | compiled (`libsukrara_libre3.so` in the APK); **not run**: it would take the sensor from Juggluco |
| Follower role + alarms (CGM Share) | available at onboarding | unchanged code from CGM Share, not re-tested tonight |
| App Link invites `https://sukrara.mauri.app/join#…` | ON | `pm get-app-links`: **`sukrara.mauri.app: verified`** ([evidence](../spikes/showcase-android/evidence/phone-app-links.txt)) |
| **Home-screen widgets** (inv. 12: value, graph, graph + value) | in the widget picker | the 3 providers are registered on the phone ([dumpsys](../spikes/showcase-android/evidence/phone-widget-providers.txt)). Emulator: picker preview + a widget on the home screen. **The user adds them; none were pinned on the phone** |
| Restart after reboot / app update | ON | the phone update at 09:29 restarted the wearer service on its own (`MY_PACKAGE_REPLACED`), with the identity, follower and settings kept |

Screenshots come from the emulator, because the phone's screen is locked with a PIN. The source there is the
debug build's synthetic one:

| | | |
|---|---|---|
| ![home](../spikes/showcase-android/evidence/01-home-wearer.png) | ![chart](../spikes/showcase-android/evidence/02-home-chart.png) | ![share](../spikes/showcase-android/evidence/05-share-qr.png) |
| Home (wearer): value, feature summary | Home, scrolled: backup, 12 h chart | Share: QR with the https App Link, "Copy Mac link" |
| ![settings](../spikes/showcase-android/evidence/03-settings-1.png) | ![settings 2](../spikes/showcase-android/evidence/04-settings-2-garmin.png) | ![treatments](../spikes/showcase-android/evidence/06-treatments-libreview.png) |
| Settings: source, sharing, Mac, status bar | Settings: status bar, Garmin, LibreView | Treatments + LibreView setup (off) |
| ![picker](../spikes/showcase-android/evidence/07-widget-picker.png) | ![widget](../spikes/showcase-android/evidence/08-widget-home.png) | |
| Widget picker: Sukrara, 3 widgets (generated preview) | Value widget on the emulator. It shows the *stale* style (struck through, "9 min ago") because the release build has no synthetic source and the emulator has no Juggluco | |

No follower-view screenshot was taken this time. The follower UI is unchanged from CGM Share; see
`spikes/nostr/android-app/evidence/06-follower-home.png`.

## What's in it (code)

`spikes/showcase-android/`, Kotlin only, GPL header on every file. It's built on CGM Share
(`spikes/nostr/android-app`) and renamed to `app.mauri.sukrara` (applicationId **`app.mauri.sukrara.poc`**),
with brand icons from `spikes/brand/assets/sukrara/`. compileSdk/targetSdk 37, minSdk 36.

| Package | From | What |
|---|---|---|
| `owner/`, `follower/`, `core/` | CGM Share (06, 07) | v2 engines, relays, BLE transport, password-manager backup, QR, alarms. `OwnerEngine` now follows the feature switches (Nostr on/off + interval, BLE on/off, source) |
| `wearer/` | new | `Live`: the one glucose fan-out. `WearerExtras`: status bar, Mac link, Garmin, LibreView, follower treatments → shared log |
| `status/` | statusbar-android (05) | `GlucoseNotification`: chip + number icon on the service's **only** notification |
| `bridge/` | glucobridge (01, 04, 08) | `MacLink` (legacy GATT service, now a class instead of a service), `XdripServer` (Garmin) |
| `treatments/`, `libreview/` | glucobridge (04) | treatment store (SPEC §1/§2 sync with the Mac), LibreView uploader, gated by `Features.libreview` |
| `direct/` + `src/main/cpp` | glucobridge `direct` (02) | Libre 3 client + JNI core. Built from `third_party/Juggluco` (this checkout's or the main checkout's) when it's present |
| `features/` | new | `Features` (all switches, prefs + StateFlow) and the adb `DebugReceiver` |
| `widgets/` | widgets-android (12) | Glance widgets, graph renderer, `WidgetUpdater` (dedupe + skip while the screen is off), the widgets' 8 h CSV store. `SukraraHooks` feeds them from `WearerExtras` (each reading, history, screen on, minute tick). `StartReceiver` restarts the service after a boot or an update. Glance 1.2.0 + WorkManager 2.12.0 (pinned, see Known issues) |

Changes from the source spikes:

- **CGM Share's debug-exported `CMD`/`DUMP` receivers are deleted.** The headless commands now go through the
  `DUMP`-protected `DebugReceiver`, in debug and release.
- **One process and one foreground service** (`OwnerService`, type `connectedDevice`). The status-bar notification
  is that service's notification, so the chip costs no extra post.
- **Invites are App Links**: `https://sukrara.mauri.app/join#i=…&t=…&x=…&r=…`, with an `autoVerify` filter on
  https/`sukrara.mauri.app`/`/join`. `cgmshare:join?…` is still accepted (deep link, scanner, paste). GlucoseBar
  only parses `cgmshare:`, so the share screen has **"Copy Mac link (cgmshare:)"**.
- Follower treatments (v2 writers) are merged into the shared treatment log, so they reach the Mac (legacy link)
  and LibreView (if enabled).

## Daily use

1. **Install** the release APK. Grant notifications and Nearby devices. Choose **"I wear the sensor" → Start fresh**
   (or *Restore from password manager* on a new phone).
2. **Backup**: on the home screen, *Save to password manager* (Proton Pass / Bitwarden / KeePassDX via Credential
   Manager). Both builds are signed with the same key, so the saved entry works for both.
3. **Status bar**: the chip needs "Live Updates" allowed. If Settings shows *Allow Live Updates for Sukrara*, tap it.
   On the phone, `adb shell appops set app.mauri.sukrara.poc POST_PROMOTED_NOTIFICATIONS allow` did it. **System
   icons and the SystemUI hook aren't in this build.** GlucoStatus, the LSPosed module from inv. 05, was uninstalled
   tonight. To get the hook back, the user must reinstall GlucoStatus, **re-enable its LSPosed module (scope
   SystemUI) and grant root again**. Sukrara doesn't feed the hook yet (see "Next").
4. **Share**: *Share with someone* shows the QR / link. The follower installs Sukrara and chooses *I follow someone*.
   The link opens the app directly (verified App Link), or `https://sukrara.mauri.app/join/` if the app isn't
   installed. The wearer checks the 4-digit code and taps *Allow*. **Nearby**: *Pair nearby* (10 min), and the
   follower picks the phone from the list.
5. **Mac (GlucoseBar)**: works with no setup through the legacy Bluetooth service. To follow end-to-end encrypted
   over Nostr + BLE instead: Share → *Copy Mac link (cgmshare:)* → GlucoseBar → *Follow someone…* → paste → Allow on
   the phone.
6. **Garmin**: nothing to do while Juggluco is the source, because Juggluco keeps port 17580 and serves the watch.
   Sukrara's server takes over only when the port is free (direct mode, Juggluco stopped). Settings → Garmin shows
   which one is serving.
7. **Widgets**: long-press the home screen → Widgets → Sukrara → *Glucose*, *Glucose graph* or *Glucose + graph*
   (resizable). They follow the wearer's readings and don't refresh while the screen is off.
8. **LibreView** (off): Treatments & LibreView → email + password → *Save login* → *Upload to LibreView*.
   **Uploaded entries can't be deleted from LibreView**, so try a throwaway account first. "Take over the account's
   device" logs the Libre app / Juggluco out of LibreView.
9. **Direct sensor** (testing only): Settings → Glucose source → *Libre 3 directly*. It needs Juggluco stopped and the
   pairing file (`files/sensor.json`, see docs/02). **Juggluco's alarms stop.**

## DebugReceiver (for investigation 11's battery lab)

The receiver is protected by `android.permission.DUMP` (only shell/root can send to it). Each switch applies at
once, without restarting the process. Settings and the receiver share the same prefs. The reply (`resultData`) is
JSON: `features` (every switch), `runtime` (last reading, source state, Mac link
advertising/subscribed, Garmin state, LibreView state, `notificationPosts` / `notificationSkipped`, `screenOn`) and
`owner` (epoch, followers, `liveSent`, `chunksSent`, BLE centrals).

```
adb -s 62240DLCH0005W shell am broadcast -n app.mauri.sukrara.poc/app.mauri.sukrara.features.DebugReceiver [extras…]
```

| Extra | Type / values | Default | Effect |
|---|---|---|---|
| *(none)* | | | read the state only |
| `nostr` | `--ez` true/false | true | relays on/off. Off closes all relay sockets: no publishes, no inbox checks |
| `nostrInterval` | `--ei` minutes 0–240 | 0 | 0 = a live event per reading + a chunk every 5 min. N = no live events to relays, one chunk batch every N min (BLE followers still get live) |
| `libreview` | `--ez` | false | arms the LibreView uploader. **It still needs a saved login.** Don't use it on the real account |
| `libreviewInterval` | `--ei` minutes 1–240 | 5 | LibreView cycle |
| `macBle` | `--ez` | true | legacy Mac GATT server + advertising (LOW_POWER, stops while a Mac is subscribed) |
| `bleFollowers` | `--ez` | true | v2 GATT server + advertising (BALANCED) |
| `chip` | `--ez` | true | Live Update chip (promoted notification). Off = colorized ongoing notification |
| `icon` | `--ez` | true | number as the small icon (off = Sukrara glyph) |
| `systemIcons`, `hook` | `--ez` | false | not in this build: always reported false |
| `screenOffSkip` | `--ez` | false | don't re-post the notification while the screen is off (catches up on screen on) |
| `garmin` | `--ez` | true | xDrip/Garmin server on 127.0.0.1:17580 (binds only if the port is free) |
| `connPriority` | `--es` default\|low\|balanced\|high | default | sensor-link priority, **direct mode only**, applied on the next sensor connection |
| `source` | `--es` juggluco\|direct | juggluco | `direct` also needs `--ez confirmDirect true`. **Takes the sensor from Juggluco** |
| `cmd` | `--es` owner\|invite\|pair\|approve\|writer | | headless setup: `owner` = create the wearer identity + start the service, `invite` = new invite link in the reply, `pair` = nearby pairing 10 min, `approve` = allow the first pending request, `writer --es f <hex> --ez on true` |

Example: `… --ez nostr true --ei nostrInterval 15 --ez chip false --ez screenOffSkip true`. The process is
`app.mauri.sukrara.poc` (one process). Partial wakelocks are held only for the work itself: 10 s per reading, 90 s
for a history backfill, 20 s for an inbox check.

## Phone state (handed over to the user at 09:30)

- **Sukrara** (`app.mauri.sukrara.poc`, release; installed 09:05, updated in place at 09:29 to add the widgets) is
  installed and running: role **wearer**, new identity
  `npub1l79v5…86st` (epoch 1), source **Juggluco**, every feature at its default (above). The permissions
  (notifications, nearby devices, camera) were granted with `pm grant`, and promoted notifications with `appops`.
  The identity is **not yet saved to a password manager**: do it from the home screen.
- Follower: **"Mac (GlucoseBar)"** (approved, code 4096). No other followers. CGM Share's old followers aren't
  carried over (new identity).
- **Uninstalled** (user request): `dev.spike.glucobridge`, `dev.spike.cgmnostr`, `dev.spike.cgmshare` (its
  identity is gone with it), `dev.spike.glucostatus` (the LSPosed module: see Daily use step 3). No brand-preview
  or `dev.spike.glucowidgets` package was installed.
- **Kept**: `dev.spike.powerlab` (investigation 11), Juggluco `tk.glucodata` (untouched, still reads the sensor and
  serves 17580), GlucoDataHandler.
- No widgets pinned (the user adds them). Bluetooth/Wi-Fi not toggled. No reboot. Nothing posted to LibreView.

## Known issues

- **GlucoseBar with both Bluetooth paths at once**: with the legacy link and the v2 link both on, the Mac's second
  connection to the same phone timed out repeatedly (the CoreBluetooth "stale identifier" problem from docs/07).
  Each path works alone: legacy (09:06), and v2 after a GlucoseBar restart with `macBle` off (09:14). After the 09:29
  update, v2 live readings arrived `via ble:` again. The Nostr path always worked. Fix on the Mac side: one connection carrying both services, or drop the legacy source once
  v2 is the default. Until then, the Mac gets its values over Nostr plus whichever BLE link connects first.
- **The legacy Mac link is unencrypted** (as in GlucoBridge): anyone nearby can read the current value and history.
  Turn it off (Settings → Mac) once GlucoseBar follows over v2.
- On a fresh install the wearer has to allow Live Updates once (the button in Settings). The emulator (API 36)
  doesn't show the chip.
- Settings writes and the receiver's reply are asynchronous: the `runtime` part of the reply can be one step behind
  a switch it just flipped. Read again to see the applied state.
- Turning BLE followers off and on quickly can leave the "connected followers" count stale (cosmetic).
- `relay.nostr.net` rejects NIP-42 AUTH ("needs serviceUrl"). This is known from 06; the other three relays serve.
- LibreView's payload still names the device "GlucoBridge" (unchanged from inv. 04, kept on purpose because that
  format was tested).
- Direct mode was not run end to end inside Sukrara: compiled only.
- The first widget build crashed at start in **release** (`Failed to create an instance of androidx.work.impl.WorkDatabase`:
  Glance's transitive WorkManager under R8). Fixed by pinning `work-runtime-ktx` 2.12.0 (as in the widgets spike)
  and a keep rule for `RoomDatabase` subclasses. Caught on the emulator before the phone update.
- Widgets show the **wearer's** readings only. In the follower role they stay empty (the follower engine doesn't
  feed them yet).
- Widget battery cost was measured on the emulator only (docs/12: ≈ 0.4 s CPU per minute-update with 3 widgets).

## Next

- Widgets in the follower role (feed `SukraraHooks.onReadings` from the follower engine), and their cost on the
  phone in investigation 11's lab.
- Feed the SystemUI hook (`HookBridge` broadcast) and the system-icon slot from `WearerExtras.render()`, behind the
  existing `hook` / `systemIcons` switches.
- GlucoseBar: one Bluetooth client for both services, and accept `https://sukrara.mauri.app/join#…` links.
- Run investigation 11's battery lab on this build with the DebugReceiver table above.
- Follower-role check on a second phone. Follower screenshots.
