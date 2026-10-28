# Investigation 10 — Sukrara showcase app: brief for the builder

One Android app that bundles every feature proven in investigations 01–09, so the user can **run it on their phone for
a few days** and judge usefulness, bugs and usability. It's still an alpha / throwaway: the final app is rebuilt
from scratch later. Optimise for: works reliably, sensible defaults, clear UI, low battery — not code beauty.

## Identity (from [docs/09-brand.md](../../../docs/09-brand.md))
- Name **Sukrara**, applicationId **`app.mauri.sukrara.poc`**, icons/colours in `spikes/brand/assets/sukrara/`
  (Android export: see its README / `brand.py export`).
- Kotlin only — **never Java source** (user rule). Prefer the newest API generations (user preference).
- Sign with `~/.android/debug.keystore` (debug and release) — Proton Pass binds saved passwords to the cert.
- Keep the GPL header on every source file (see any existing `.kt` file).

## Features to bundle (reuse code — copy from the spikes, don't reinvent)
| Feature | Source spike / report | Default |
|---|---|---|
| Glucose from **Juggluco** (127.0.0.1:17580, SSE `/x/live`) | `spikes/glucobridge-android` (juggluco flavor), docs/01 | **ON** (keeps Juggluco's alarms) |
| Glucose **directly from the Libre 3** (no Juggluco) | glucobridge `direct` flavor, docs/02 | opt-in toggle with a clear warning ("Juggluco must be stopped; its alarms stop") |
| **Status bar**: Live Update chip + glucose bitmap notification icon (+ system icons / LSPosed hook only if cheap) | `spikes/statusbar-android`, docs/05 | chip + icon ON |
| **Treatments** (insulin/carbs/notes) + **LibreView upload** | glucobridge (TreatmentStore, LibreView uploader), `spikes/libreview/SPEC.md`, docs/04 | **OFF until the user sets it up** |
| **Sharing over Nostr** (wearer + follower roles, QR invite + match code, revoke, password-manager backup/restore) | `spikes/nostr/android-app` (CGM Share), PROTOCOL-v2, docs/06 | wearer role publishes; follower role available |
| **Bluetooth followers** (same v2 events over BLE, nearby pairing, writers) | spikes/nostr/android-app + docs/07 | ON |
| **Garmin** watch server (xDrip-compatible local HTTP) | docs/08 (watch for the port clash with Juggluco on 17580) | ON |
| Follower **alarms** (low/high/no data) | CGM Share | ON in follower role |
| **Home-screen widgets** (value, graph, graph+value; responsive sizes) — only if investigation 12 finished | `spikes/widgets-android`, docs/12 | available in the widget picker |
| **Mac follower parity** (GlucoseBar pairs over Nostr v2 + BLE like the Android follower) | `spikes/menubar/FOLLOWER.md` | verify GlucoseBar can pair with Sukrara over both |

## Hard rules for tonight (the user is asleep, no supervision)
- **Never take the sensor from Juggluco** (no direct-mode test on the phone, no force-stopping Juggluco): the user's
  night-time alarms depend on it. Direct mode is built and compiled, tested only if it can be done without the phone.
- **Never post to LibreView.** It's the user's real, doctor-facing account (records can't be deleted). Leave the
  upload off with a setup screen; never print tokens/passwords.
- Never print or commit secret keys (nsec), never commit `secrets/`, build outputs or APKs.
- Only adb with `-s <serial>`. Phone: `62240DLCH0005W` (USB). Don't toggle Bluetooth/Wi-Fi globally, don't reboot.
- The user wants to try this app: after installing, **force-stop (don't uninstall)** the older spike apps that would
  duplicate its work (dev.spike.glucobridge, dev.spike.cgmnostr, dev.spike.cgmshare, dev.spike.glucostatus) and say so
  in the report so the user can uninstall them. Don't touch Juggluco (tk.glucodata) or GlucoDataHandler.
- Status-bar hook / system icons need user actions (LSPosed module enablement, root grant) — document, don't block.

## Battery lab requirements (investigation 11 will measure this app — see `/Users/blue/Projects/cgm-reading-mac-spike/spikes/power/README.md`)
- An adb-only **DebugReceiver** protected by `android.permission.DUMP` that toggles each feature independently without
  restarting the process and returns the current state in `resultData`: each upload on/off **and its interval**
  (Nostr, LibreView), Mac BLE link, status-bar surfaces (chip, icon, system icons, hook), "don't refresh the
  notification while the screen is off", sensor connection priority (direct mode only), local servers (Garmin) on/off.
- Single process, no partial wakelocks beyond the work itself, release build (R8).

## Deliverables
- Project `spikes/showcase-android/`, release APK built, **installed on the phone and running** (wearer role, Juggluco
  source) with GlucoseBar on the Mac receiving from it over BLE.
- A short manual test pass on the phone of every default-ON feature; screenshots (no secrets) in
  `spikes/showcase-android/evidence/` for the README: home, status bar chip, share screen, follower view (emulator is
  fine), settings/toggles, Garmin.
- `docs/10-showcase.md`: what's in it, defaults, how to use it daily (setup steps for LibreView / sharing / password
  backup / Garmin / status-bar hook), the DebugReceiver toggle table (component + extras) for investigation 11,
  known issues.
