# 10 — Sukrara: the showcase app

*2026-10-16 · investigation report · Pixel 10 Pro (Android 17), Juggluco + Libre 3 Plus, Sukrara for Mac (was GlucoseBar)*

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
| Garmin watch server (xDrip+-compatible, `127.0.0.1:17580`) | **ON** | Port taken by Juggluco, as expected: Juggluco keeps serving the watch. On the emulator (no Juggluco) it listens. **In the follower role it serves the followed person** (emulator: `/sgv.json` returned the phone's values) |
| Treatments (insulin / carbs / notes) | available | emulator: logged "2.5 U rapid · breakfast" |
| LibreView upload | **OFF** (setup screen) | never enabled, nothing posted |
| Direct Libre 3 source (inv. 02) | **OFF**, opt-in with a warning | compiled (`libsukrara_libre3.so` in the APK); **not run**: it would take the sensor from Juggluco |
| Follower role + alarms (CGM Share) | available at onboarding | **gap audit, 10:10–10:42:** an emulator running the release build followed the phone's wearer over Nostr (headless invite → `cmd follow` → match code **3308** on both sides → `cmd approve`). Live readings + 24 h of chunks, 0 rejected events |
| App Link invites `https://sukrara.mauri.app/join#…` | ON | `pm get-app-links`: **`sukrara.mauri.app: verified`** ([evidence](../spikes/showcase-android/evidence/phone-app-links.txt)) |
| **Home-screen widgets** (inv. 12: value, graph, graph + value) | in the widget picker | the 3 providers are registered on the phone ([dumpsys](../spikes/showcase-android/evidence/phone-widget-providers.txt)). The user has since pinned a *Glucose + graph* widget on the phone. **Follower role too** (gap audit): the emulator follower's value and graph + value widgets show the phone's real readings |
| **Status bar for followers** (gap audit) | follows the chip/icon switches | the follower service's only notification is now the wearer's chip + number icon, showing the followed person's value (emulator: number icon in the status bar, colored card in the shade) |
| **Nightscout read API** (inv. 03 tier 1, gap audit) | **ON**, `127.0.0.1:17581`; LAN **OFF** | phone (wearer, next to Juggluco): `api_check.py` **17/17** ([log](../spikes/showcase-android/evidence/phone-wearer-nightscout-api-check.txt)); emulator follower: 17/17 ([log](../spikes/showcase-android/evidence/emu-follower-nightscout-api-check.txt)); LAN mode: 401 without / with a wrong secret, 200 with `?token=` or `api-secret` ([log](../spikes/showcase-android/evidence/emu-follower-nightscout-lan-auth.txt)) |
| Restart after reboot / app update | ON | the phone update at 09:29 restarted the wearer service on its own (`MY_PACKAGE_REPLACED`), with the identity, follower and settings kept. Updated in place again at 10:25 and 10:33 (gap-audit build): wearer back at once. **Followers now restart too**: emulator log `Background started FGS: Allowed … MY_PACKAGE_REPLACED` for `FollowerService` |

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

**Follower view with real data** (gap audit, 10:17–10:42). The emulator runs the release build as a follower of the
phone's wearer, over Nostr, so these are the user's real readings:

| | | |
|---|---|---|
| ![follower home](../spikes/showcase-android/evidence/09-follower-home-real-data.png) | ![follower widget](../spikes/showcase-android/evidence/10-follower-widget-combined-real-data.png) | ![follower notification](../spikes/showcase-android/evidence/12-follower-notification-real-data.png) |
| Follower home: value, 12 h chart, alarms, local servers. The history starts at 09:00 here (see Known issues) | *Glucose + graph* widget in the follower role | The follower's notification: number icon + colored card ("following · via relay.primal.net") |
| ![value widget](../spikes/showcase-android/evidence/11-follower-widget-value-real-data.png) | ![after fix](../spikes/showcase-android/evidence/13-follower-widget-combined-after-r8-fix.png) | ![bug](../spikes/showcase-android/evidence/13b-combined-widget-r8-merge-bug.png) |
| *Glucose* widget (follower) | Graph + value after a restart, **with the R8 fix**. Struck through: the overloaded emulator had lost DNS for 9 min | The bug the audit found: after a process restart, *Glucose + graph* lost its value header (R8 merged two widget classes) |

## What's in it (code)

`spikes/showcase-android/`, Kotlin only, GPL header on every file. It's built on CGM Share
(`spikes/nostr/android-app`) and renamed to `app.mauri.sukrara` (applicationId **`app.mauri.sukrara.poc`**),
with brand icons from `spikes/brand/assets/sukrara/`. compileSdk/targetSdk 37, minSdk 36.

| Package | From | What |
|---|---|---|
| `owner/`, `follower/`, `core/` | CGM Share (06, 07) | v2 engines, relays, BLE transport, password-manager backup, QR, alarms. `OwnerEngine` now follows the feature switches (Nostr on/off + interval, BLE on/off, source) |
| `wearer/` | new | `Live`: the one glucose fan-out. `WearerExtras`: status bar, Mac link, Garmin + Nightscout API, LibreView, follower treatments → shared log. `follower/FollowerExtras` (gap audit): the same widgets, status bar and local servers fed by the follower DB |
| `status/` | statusbar-android (05) | `GlucoseNotification`: chip + number icon on the service's **only** notification |
| `bridge/` | glucobridge (01, 04, 08), nostr (03) | `MacLink` (legacy GATT service, now a class instead of a service), `XdripServer` (Garmin, plus a second listener), `NightscoutApi` (inv. 03 tier 1, ported from `follower.py`), `LocalServers` (both listeners per role, live switches) |
| `treatments/`, `libreview/` | glucobridge (04) | treatment store (SPEC §1/§2 sync with the Mac), LibreView uploader, gated by `Features.libreview` |
| `direct/` + `src/main/cpp` | glucobridge `direct` (02) | Libre 3 client + JNI core. Built from `third_party/Juggluco` (this checkout's or the main checkout's) when it's present |
| `features/` | new | `Features` (all switches, prefs + StateFlow) and the adb `DebugReceiver` |
| `widgets/` | widgets-android (12) | Glance widgets, graph renderer, `WidgetUpdater` (dedupe + skip while the screen is off), the widgets' 8 h CSV store. `SukraraHooks` feeds them from `WearerExtras` or `FollowerExtras` (each reading, history, screen on, minute tick). `StartReceiver` restarts the role's service (wearer or follower) after a boot or an update. R8 keep rule for the Glance classes (see Known issues). Glance 1.2.0 + WorkManager 2.12.0 (pinned, see Known issues) |

Changes from the source spikes:

- **CGM Share's debug-exported `CMD`/`DUMP` receivers are deleted.** The headless commands now go through the
  `DUMP`-protected `DebugReceiver`, in debug and release.
- **One process and one foreground service** (`OwnerService`, type `connectedDevice`). The status-bar notification
  is that service's notification, so the chip costs no extra post.
- **Invites are App Links**: `https://sukrara.mauri.app/join#i=…&t=…&x=…&r=…`, with an `autoVerify` filter on
  https/`sukrara.mauri.app`/`/join`. `cgmshare:join?…` is still accepted (deep link, scanner, paste). Sukrara for Mac
  now accepts both forms (gap audit); the share screen keeps a **"Copy cgmshare: link (older Mac builds)"** button.
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
5. **Mac (Sukrara for Mac, `spikes/menubar/build/Sukrara.app`, was GlucoseBar)**: works with no setup through the
   legacy Bluetooth service. To follow end-to-end encrypted over Nostr + BLE instead: Share → *Copy link* (the
   `https://sukrara.mauri.app/join#…` link or the `cgmshare:` one) → Sukrara for Mac → *Follow someone…* → paste →
   Allow on the phone. Low / high / no-data alarms on the Mac too. The old GlucoseBar follow state is migrated once.
   Details: [`spikes/menubar/FOLLOWER.md`](../spikes/menubar/FOLLOWER.md).
6. **Garmin**: nothing to do while Juggluco is the source, because Juggluco keeps port 17580 and serves the watch.
   Sukrara's server takes over only when the port is free (direct mode, Juggluco stopped). Settings → Garmin shows
   which one is serving. On a **follower's** phone Sukrara's server takes the port and serves the followed person.
7. **Widgets**: long-press the home screen → Widgets → Sukrara → *Glucose*, *Glucose graph* or *Glucose + graph*
   (resizable). They show the wearer's readings, or on a follower's phone the followed person's, and don't refresh
   while the screen is off.
8. **LibreView** (off): Treatments & LibreView → email + password → *Save login* → *Upload to LibreView*.
   **Uploaded entries can't be deleted from LibreView**, so try a throwaway account first. "Take over the account's
   device" logs the Libre app / Juggluco out of LibreView.
9. **Direct sensor** (testing only): Settings → Glucose source → *Libre 3 directly*. It needs Juggluco stopped and the
   pairing file (`files/sensor.json`, see docs/02). **Juggluco's alarms stop.**
10. **Nightscout API** (Settings → Nightscout API, or the follower's home screen): `http://127.0.0.1:17581` on the
   phone, on by default, works next to Juggluco. Apps on the same phone (xDrip+ follower, a Garmin app in
   Nightscout mode) point at it. *Also on this network* binds it to Wi-Fi so Home Assistant's
   built-in Nightscout integration, Nightguard or an ESP32 clock can read it at `http://<phone IP>:17581`. They need
   the **API secret** shown there (HA: "API key"; Nightguard/Garmin: `?token=`). Plain HTTP, so only on trusted
   networks. Treatments come from the shared log (rapid → Correction Bolus, carbs → Carb Correction, long/note →
   Note).

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
| `nightscout` | `--ez` | true | Nightscout read API on 127.0.0.1:17581 (inv. 03 tier 1; both roles) |
| `nightscoutLan` | `--ez` | false | the same API on all interfaces; non-loopback callers need the API secret |
| `connPriority` | `--es` default\|low\|balanced\|high | default | sensor-link priority, **direct mode only**, applied on the next sensor connection |
| `source` | `--es` juggluco\|direct | juggluco | `direct` also needs `--ez confirmDirect true`. **Takes the sensor from Juggluco** |
| `cmd` | `--es` owner\|invite\|pair\|approve\|writer\|follow | | headless setup: `owner` = create the wearer identity + start the service, `invite` = new invite link in the reply, `pair` = nearby pairing 10 min, `approve` = allow the first pending request, `writer --es f <hex> --ez on true`. **Follower side** (e.g. an emulator following the phone): `follow --es link <https or cgmshare link> [--es name <n>]` sets the follower role and sends the request; the reply then has a `follower` object (status, code, epoch, readings, rejects, last). Start the follower service by opening the app once (a broadcast can't start a foreground service) |

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

**Gap audit update (10:10–10:45):** the phone was updated in place twice (10:25 and 10:33, data kept, wearer back at
once, no crash). New: Nightscout API listening on `127.0.0.1:17581` (LAN off), followers' widgets and status bar,
the Glance R8 fix. The user had pinned a *Glucose + graph* widget by then. A second follower was approved headlessly:
**"Emulator follower inv10"** (code 3308), a test leftover. Removing it in Sukrara rotates the key; Sukrara for Mac
and any other follower pick up the new key by themselves. The previous APK is kept at
`/tmp/sukrara-backup/phone-before-audit.apk` (Mac, temporary) in case a rollback is needed. The repo build differs from the phone's 10:33 build only in UI labels ("GlucoseBar" → "Sukrara for Mac"); the user unplugged the phone before it could be installed.

## Known issues

- **Sukrara for Mac (was GlucoseBar) with both Bluetooth paths at once** (still open after the gap audit; no cheap fix found): with the legacy link and the v2 link both on, the Mac's second
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
- ~~Widgets show the **wearer's** readings only.~~ Fixed in the gap audit: `FollowerExtras` seeds them from the
  follower DB and feeds every stored batch.
- **Fixed (gap audit): after a process restart the release build drew *Glucose + graph* as the plain graph.** R8
  had horizontally merged `GraphWidget` into `CombinedWidget` (`mapping.txt`: both → `g8.d`), and Glance keys its
  widget bookkeeping by that class name, so `GraphWidget().updateAll()` also rendered into the combined widget.
  A keep rule for `GlanceAppWidget` / `GlanceAppWidgetReceiver` subclasses keeps them apart; verified on the
  emulator after a restart + update. The *Glucose + graph* widget the user pinned on the phone could show this until the 10:33 update.
- **Thin history for a new follower**: the emulator follower first got only the last ~1.5 h of chunks (plus one
  24-h-old hour), although the wearer has 12 h+. When the phone was updated at 10:25 the wearer republished its
  last 24 h and the follower filled in. Likely the 25-chunk burst at 09:05 didn't all stick on the relays (pacing /
  rate limits). Next: republish missing hours on a follower's request, or periodically.
- On some Android 16 (API 36) images `Notification.Builder.setRequestPromotedOngoing` doesn't exist
  (`NoSuchMethodError`, the emulator follower crashed on it). The chip now falls back to the colorized notification.
- Widget battery cost was measured on the emulator only (docs/12: ≈ 0.4 s CPU per minute-update with 3 widgets).

## Next

- Widget cost on the phone in investigation 11's lab (both roles).
- Feed the SystemUI hook (`HookBridge` broadcast) and the system-icon slot from `WearerExtras.render()`, behind the
  existing `hook` / `systemIcons` switches.
- Sukrara for Mac: one Bluetooth client for both services (the https links are done).
- Run investigation 11's battery lab on this build with the DebugReceiver table above.
- Follower-role check on a second real phone (the emulator check and screenshots are done).
- The remaining rows of the gap audit below.

## Gap audit (2026-10-16)

Every report's result and "not done" sections (01–09, 12, 13; 11 is in progress elsewhere) checked against what
Sukrara for Android and Sukrara for Mac actually contain. **Status** is after this session's work (10:00–10:50).
Value/effort: H/M/L.

| Inv. | Proven capability | Android | Mac | Gap | Value / effort | Status |
|---|---|---|---|---|---|---|
| 01 | Juggluco → phone → Mac over BLE (legacy GATT), Mac offline | yes (`MacLink`) | yes | none | | ok |
| 01 | Mac reads Juggluco over Wi-Fi | n/a | yes | none | | ok |
| 01/02 | Mac reads the Libre 3 itself | no | no | decided against (fallback only, docs/01) | L / H | won't do |
| 02 | Phone reads the sensor itself (no Juggluco) | compiled, off | n/a | never run inside Sukrara | H / M (needs the user to give up Juggluco's alarms for a while) | open, user decision |
| 02 | Sensor activation (NFC) | no | n/a | never proven anywhere | H / H | out of scope (new spike) |
| 03 | E2E Nostr stream, hourly chunks, 12 h backfill | yes | yes (v2) | thin backfill for a new follower until the wearer republishes (Known issues) | M / M | open |
| 03 | **Nightscout read API (tier 1)**, HA / xDrip+ / Nightguard / clocks / Garmin | only the xDrip subset on 17580, and never on the phone (Juggluco holds the port) | no | API on its own port, in both roles | **H / M** | **done**: `127.0.0.1:17581`, LAN opt-in with API secret, 17/17 on the phone and on the follower |
| 03 | Home Assistant validated (stock `nightscout` integration) | — | — | not re-run against Sukrara (`ha_check.py` needs Docker) | M / L | open (cheap re-check) |
| 03 | HA add-on as always-on follower / "no data" watchdog | no | no | separate product | M / H | out of scope |
| 03 | Tier 2 upload endpoint (xDrip+/AAPS → Sukrara) | no | no | not built anywhere | M / M | open |
| 04 | Phone → LibreView (treatments + glucose) | yes, off | yes | real-account run is the user's call | M / L | ok (off by design) |
| 04 | Treatments sync phone ↔ Mac (legacy BLE) | yes | yes | none | | ok |
| 04/07 | Followers add treatments (v2 writers) | yes | yes (send only) | — | | ok |
| 07 | **Followers see treatments** | no (follower drops the treatment chunk) | no (v2) | decode `treatmentsC` chunks, show in the 12 h chart / list, serve in the follower's Nightscout API | M / M | open |
| 05 | Live Update chip + number icon | yes (wearer) | n/a | follower had a plain text notification | **M / L** | **done**: follower gets the same chip/icon with the followed value |
| 05 | System icons (root) and LSPosed SystemUI hook | no | n/a | — | — | **owned by session 02** |
| 06 | Invite QR / link, match code, Allow/Deny, remove + key rotation | yes | yes | — | | ok |
| 06 | Password-manager save / restore (Credential Manager), NIP-49 paste | yes | n/a | proven with KeePassDX on an emulator only; Proton Pass / Bitwarden on the real phone need the user | H / L (user, 5 min) | open, user action |
| 06 | Printable recovery sheet | no | no | — | L / L | open |
| 06 | Camera QR scanning | yes (CameraX + zxing-cpp) | QR from image only | never tested with a real camera | M / L (user) | open, user action |
| 06 | Follower alarms low / high / no data | yes | **no** | Mac had none | **H / L** | **done** (Mac `Alarms.swift`, same thresholds; not yet seen firing) |
| 06/07 | Follower survives reboot / app update | wearer only | n/a | `FollowerService` never restarted | **H / L** | **done** (`StartReceiver` for both roles; verified on update) |
| 07 | BLE followers + nearby pairing | yes | yes | — | | ok |
| 07 | Mac with legacy + v2 BLE at once | — | second connection times out | one central for both services, or drop legacy | M / M | open (no cheap fix) |
| 07 | GATT abuse limits, `INFO` privacy gating | no | — | — | M / M | open |
| 08 | Garmin via the xDrip+ server | wearer, only if 17580 is free | n/a | a follower phone (e.g. a parent with a Garmin) couldn't serve the watch | M / L | **done**: follower serves 17580 |
| 08 | Own watch app / push via Connect IQ | no | n/a | next spike | H / H | out of scope |
| 09 | Brand: name, icon, ids | yes | **no** (GlucoseBar, `dev.spike.*`) | Mac app not renamed | M / L | **done**: `Sukrara.app`, `app.mauri.sukrara.poc`, brand icon, state migrated |
| 10 | App Link invites `https://sukrara.mauri.app/join#…` | yes | **no** (`cgmshare:` only) | Mac couldn't paste the https link | **H / L** | **done** (both forms; https clicks on the Mac still open the browser: Universal Links need a Team ID) |
| 12 | Home-screen widgets | wearer only | n/a | **followers saw empty widgets** | **H / L** | **done** (follower DB as source) |
| 12 | Widget rendering in release | combined drawn as graph after a restart (R8 merge) | n/a | found during this audit | H / L | **done** (keep rule) |
| 12 | Real-phone widget screenshot, 1×1 / 2×2 layouts, config screen, mmol/L | no | n/a | — | M / L (screenshots need the unlocked phone) | open |
| 13 | macOS widgets (WidgetKit) | n/a | no | blocked by signing: needs an Apple Team ID certificate | M / L once a cert exists | blocked (user decision) |
| 13 | Mac local reading DB (beyond 3 h) | n/a | no | every launch backfills 12 h / ~25 h | L / M | open |
| 10 | Screenshots | emulator only | — | no follower view, no real data | M / L | **done for the follower** (emulator, real data); wearer screenshots need the unlocked phone |

## What's left

The remaining polish (user requests, known issues, rules for whoever continues) is tracked in
[`spikes/showcase-android/NEXT.md`](../spikes/showcase-android/NEXT.md).
