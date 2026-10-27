# Sukrara V1 plan (planner, 2026-10-27 ~04:20 local)

> Record of the Sukrara V1 night run (2026-10-27), copied from the run's working file `plan/PLAN.md` with paths
> made readable. `OPS/…` paths name the run's scratch files (briefs, reviews, issue lists), which are not in
> the repo. Index: [README.md](README.md).

Inputs: OPS/inventory/*, OPS/research/*, OPS/issues/*, docs/FEATURES.md, README.md, CLAUDE.md, ORCH.md decisions D1–D3.
Paths are **post-move** (`poc/spikes/…`, `poc/spec/…`). `K/` = `poc/spikes/showcase-android/app/src/main/kotlin/app/mauri/sukrara/`,
`GB/` = `poc/spikes/menubar/Sources/GlucoseBar/`, `WM/` = `poc/spikes/widgets-macos/`.
Briefs: `OPS/plan/chunks/NN-<id>.md`. Every chunk also follows process.md (G0→G3, heartbeat, issues file).

> **Owner decision (overrides D1, ~04:15): V1 uses NEW ids, clean names, NO data migration** — full text in
> `d1-change.md` (binding; read it with this plan). Android `app.mauri.sukrara`, Mac `app.mauri.sukrara`
> (+`.widget`), App Group `QWUV9H4A3G.app.mauri.sukrara`; installs side by side with the PoC; first-run setup flow
> (restore identity from the showcase's recovery code); dedicated Android release keystore; versionCode 0.90.0 = 90000.
> This plan below is already revised for it. **Then revised again (~04:50) for changes.md (P1–P10, A1–A10);
> `dag-v2.md` is the authoritative chunk table.**

Verified tonight (not only from inventories): Xcode.app + xcodegen + Swift 6.4 installed; JDK 17/21 via brew
(`/opt/homebrew/opt/openjdk@21`); Android SDK at `~/Library/Android/sdk` (emulator binary at `sdk/emulator/emulator`,
`android` CLI at `~/.local/bin/android`); owner's Mac defaults domain has **no** `url`/`secret`/`nostr`/`alertSettings`
keys (so Mac Juggluco-LAN and Nostr-v1 sources are unused; Mac alerts run on defaults/mirror); it has `quickKind`,
`NSWindow Frame Sukrara{Report,Settings,Treatments}`, `lv*` leftovers. Homebrew workflow already skips prereleases
(`if: … !github.event.release.prerelease`).

---
## 1. Target repo layout

```
apps/
  android/            Gradle root of the V1 Android app (own settings.gradle.kts, wrapper, build-logic/)
  macos/              SwiftPM root of the V1 Mac app (Package.swift, Sources/, Tests/, Bundle/ scripts+plists)
contracts/            THE cross-platform truth, read by both test suites (§4). Changing it triggers both CIs.
assets/brand/         app icons + brand art used by both apps and docs (moved from poc/spikes/brand/assets/sukrara)
poc/                  frozen PoC (spikes/, spec/): buildable, excluded from all rules except secrets/large/java
spec/  → poc/spec/    (r0 moved it; the public OGS page links the poc copy until a V1 spec round)
third_party/          gitignored reference checkouts; third_party/Juggluco = Libre 3 core build input (pinned, §2.8)
tools/
  check/              run.sh + check.py retargeted to apps/ + contracts/ (§1.1)
  hooks/              unchanged (pre-commit static, commit-msg coupling)
  settings/gen.py     schema → apps/android/core/data/…/SettingsSpec.kt + apps/macos/Sources/SukraraCore/Settings/SettingsSpec.swift
  release/            version.sh (reads VERSION), make-keystore.sh (one-time), android-cert.sha256, android-release.sh,
                      mac-release.sh, verify-apk.sh (signer + versionCode + .so + no debug classes)
  install.sh          phone|mac → apps/ builds (package app.mauri.sukrara); same refusals as today + signer-cert +
                      native-core verification; mac: moves a PoC bundle to "Sukrara Showcase.app" first, never deletes
docs/                 FEATURES.md, QA.md, 10-showcase.md, 20-publishing.md, ARCHITECTURE.md (new), DEVELOPMENT.md (new,
                      replaces NEXT.md §5), CHANGELOG.md (new, root-linked), investigation reports NN-*.md stay in place
VERSION               single version source, `0.90.0` (both apps read it)
.github/workflows/    checks.yml rewritten (changes job + android + mac + gate), website.yml, homebrew-tap.yml (unchanged)
```

### 1.1 Rules / tooling become
- **check.py**: constants `ANDROID=apps/android`, `MAC=apps/macos`, `CONTRACTS=contracts`. Every per-app rule scans
  `apps/**` only; `poc/**` is excluded from everything except `secrets`, `large`, `java`. A file named in
  surfaces.json/parity.json/allow.json that does not exist yet is a **NOTE ("pending V1 file")**, not a failure, until
  `tools/check/v1-complete` exists (created by the release chunk) — lets wave-1 land rules before code.
- **parity.json** groups re-pointed: `graph` (core/graph ↔ Sources/SukraraGraph), `alerts` (core/alerts ↔ SukraraAlerts),
  `alert-scenarios`/`alert-spec` (contracts/alerts/** ↔ both engines), `protocol-v2`/`identity` (contracts/protocol/*.md ↔
  core/protocol + SukraraShare), `treatments`, `export`, `strings` (all `apps/android/**/res/values{,-es}/strings.xml`
  per module), `settings` (contracts/settings ↔ both SettingsSpec). PoC sides dropped.
- **header**: new V1 header (3 lines): `// Sukrara — https://github.com/mauriciabad/Sukrara` / `// Copyright (C) 2026 Maurici Abad`
  / `// SPDX-License-Identifier: GPL-3.0-or-later` (`#` for py/sh, `<!-- -->` for xml). gen.py HEADER updated.
- **images**: allowed also `apps/*/**/src/test/snapshots/**`, `apps/macos/Tests/**/__Snapshots__/**`, `assets/brand/**`,
  `poc/spikes/*/evidence/**` (synthetic data only, as before).
- **strings**: scope `apps/android/**/src/main/kotlin/**/*.kt`; each module owns `res/values{,-es}/strings.xml`.
- **cleartext/network**: `allow.json` entries re-derived per chunk (each chunk adds its own entries with reasons).
- **CI** (`checks.yml`): job `changes` (dorny/paths-filter@v4: android = `apps/android/** contracts/** tools/** VERSION`,
  mac = `apps/macos/** contracts/** tools/** VERSION assets/**`, poc = `poc/**`); `rules` always (`run.sh --range`);
  `android` (JDK 21: `./gradlew --no-daemon check` = unit tests + lint + spotlessCheck + Roborazzi verify);
  `mac` (macos-15/26: `swift build`, `swift test`, `Bundle/bundle.sh --unsigned` smoke incl. appex); `poc` jobs (the old
  ones at new paths) only when `poc/**` changes; one `gate` job `if: always()` = the only required check.
- **CLAUDE.md** rewritten for V1 (same rules, new paths: `apps/`, `contracts/`, poc frozen; "debug never fakes in
  release" now = *compiled out* (`src/debug`, `#if DEBUG`); "one graph" names core/graph + SukraraGraph; the Mac
  "one gate" rule becomes `LaunchMode` (pure, unit-tested) instead of LaunchGuard's source grep).
- **README**: rewritten around V1 0.90.0 (features, install incl. "upgrading from 0.11.x keeps your data", repo
  layout). **FEATURES.md**: every chunk updates its sections; cut features move to a "Not in V1 (yet)" section with
  the issue link. **docs/10-showcase.md**: new section "V1 port (0.90.0): what came in, what was dropped and why" (§8).

---
## 2. Android V1 architecture (`apps/android`)

### 2.1 Build
- Gradle 9.3.1 wrapper, AGP 9.1.1 (built-in Kotlin), Kotlin 2.3.20, compileSdk/targetSdk 37, minSdk 36, arm64-v8a.
  **JDK 21 toolchain everywhere** (Robolectric/Roborazzi need it; no more silently excluded tests — the build fails if
  JDK 21 is missing).
- `gradle/libs.versions.toml` (PoC versions; drop settingslib-spa deps, material-icons-extended only if needed,
  `com.google.android.material` alpha dropped). Add: kotlinx-coroutines-test, Roborazzi (+ compose rule), Spotless+ktlint
  (compose-rules), turbine.
- `build-logic/convention` (included build): `sukrara.android.application`, `sukrara.android.library`,
  `sukrara.android.compose`, `sukrara.jvm.library`, `sukrara.contracts` (sets test `systemProperty("sukrara.contracts",
  <repo>/contracts)` + `inputs.dir`, so Gradle caching sees fixture changes), `sukrara.quality` (lint
  `warningsAsErrors` curated + `lint-baseline.xml`, `checkReleaseBuilds`, Spotless ktlint).
- **Ids**: applicationId = namespace root `app.mauri.sukrara` (debug builds too, no suffix: one install per phone).
- **Version**: `VERSION` → versionName `0.90.0`, **versionCode = major·1 000 000 + minor·1 000 + patch** = 90000,
  monotonic from here. `BuildConfig.PRERELEASE = major == 0`.
- **Signing**: dedicated release keystore `~/.android/sukrara-release.jks` (alias `sukrara`), created once by chunk 01
  (`tools/release/make-keystore.sh`), password in the macOS Keychain (`sukrara-android-release`) or env; read by
  `signingConfigs.release` via `SUKRARA_KEYSTORE`/`SUKRARA_KEYSTORE_PASSWORD`. Debug builds use the debug key.
  `verify-apk.sh` checks the signer SHA-256 = `tools/release/android-cert.sha256`; backup procedure in docs/20.
- **Libre 3 native core**: module `:sensor` CMake builds `libsukrara_libre3.so` from `third_party/Juggluco`, located via
  Gradle property `sukrara.juggluco` / env `SUKRARA_JUGGLUCO` / default `<git-common-dir>/../third_party/Juggluco`
  (works in worktrees). `apps/android/sensor/juggluco.lock` pins `a983788a…`. **Any release variant task fails** if
  the dir is missing, the revision differs, or the merged APK lacks `lib/arm64-v8a/libsukrara_libre3.so` (a
  `verifyNativeCore` task wired into `assembleRelease`). Debug/CI builds without it log a loud warning; the app shows
  "Libre 3 core missing".

### 2.2 Modules (17; package root `app.mauri.sukrara`)
| module | type | owns |
|---|---|---|
| `:app` | app | Application, `AppGraph` (manual DI), MainActivity + Navigation 3 graph, manifest aggregation, `src/debug/` (DebugReceiver, DebugFakes, SyntheticSource, StrictMode) |
| `:core:model` | JVM, **additive-only** | Reading, Trend, GlucoseUnit/format, Treatment, SensorInfo/Profile, Role, Durations, Clock, **`GlucoseState` + `GlucoseHub` interface** (A10: features/widgets never depend on `:monitor`) |
| `:core:testing` | JVM (test-only) | `Contracts` helper (reads `sukrara.contracts`), FakeClock, test dispatchers, fake relay |
| `:core:alerts` | JVM | AlertEngine (pure), AlertSettings (+ PoC JSON codec, field `v`), episodes, Mute all / Remind less / quiet, MuteCopy |
| `:core:protocol` | **pure JVM, no nostr-sdk** (A5) | Nostr v2 wire (PROTOCOL-v2/identity), Derive, Schnorr, invites, roster/epochs, payloads (readings, treatments, settings mirror, profile), BLE share framing/INFO, `NostrCrypto` interface |
| `:core:data` | Android lib | Preferences DataStore (schema keys) + typed DataStore (kotlinx.serialization), SecureStore (Keystore, aliases `sukrara.<purpose>`), `FileStore` (tmp+rename, quarantine `.corrupt-<ts>`), Room `SukraraDb` (readings, treatments, outbox, rejects; exported schema + migration tests from v1 on), one OkHttp + kotlinx JSON, SettingsSpec (generated) |
| `:core:designsystem` | Android lib (built by chunk 08) | theme (dark-first), motion (springs, reduced-motion), haptics helper, settings rows/switch/list/slider, `NumberDialog`, banner slot (≤1), icons (replaces settingslib-spa) |
| `:core:graph` | Android lib | the one card renderer: GlucoseCard, GlucoseGraph, layout (CardLayout), Freshness, stats/metrics, `CardSurface` enum, `GlucoseCard.face` text |
| `:monitor` | Android lib | **GlucoseHub** (single state), `MonitoringService` (one FGS, role-parameterised), BootReceiver, Bluetooth-state receiver, HealthCheck alarm, channel registry + notification builders, Live notification, FreshnessTicker, alert runtime (scheduler, notifier, AlertSoundProvider `${applicationId}.alertsound`, haptics, action receiver) |
| `:sensor` | Android lib (+JNI) | GattLink (op timeouts, BT recovery), Libre 3 (driver, NFC start/takeover, `Libre3Crypto` JNI — `jni.c` symbol names match V1's class), PairingStore (V1 format `files/sensor/pairing.json`, secrets via SecureStore), ReceiverId, Juggluco web source, sensor life |
| `:share` | Android lib | SDK-backed crypto (`org.nostrdevkit:nostr-sdk:0.45.1` + JNA aar) behind `:core:protocol` interfaces, relay transport (RelayPool behind `RelayTransport`), identity-conflict detection (A3), OwnerEngine use-cases, FollowerEngine use-cases, BLE share (GATT server/client, HELLO) |
| `:integrations` | Android lib | upload queue, Nightscout, Tidepool, LibreView, Health Connect, local broadcasts, local servers :17580/:17581, CSV export, calendar |
| `:pen` | Android lib (pure packages) | PenEngine + opennov, NFC tap/read, ledger (`pens.json`), WorkManager job "pen" |
| `:widgets` | Android lib | Glance widgets (value / graph / combined receivers), WidgetConfigActivity, updater (dedupe, screen-off skip) |
| `:feature:home` | Android lib | home (card, chart, treatments list, report card, single banner slot, no-data reasons, reliability row, "Finish setup" card) |
| `:feature:settings` | Android lib | settings overview + one file per page, About + update check, settings transfer |
| `:feature:sharing` | Android lib | **first-run setup flow** (welcome, role, identity restore/start, permissions checklist, sensor hand-off, alert review), backup, share/invite QR, approve, followers; follower join (link/QR/nearby), following page |
| `:feature:sensor` | Android lib | new-sensor flow (state machine VM; "stop it in the showcase first"), sensor end reminders/countdowns UI |
| `:feature:treatments` | Android lib | treatment dialog, QuickTreatmentActivity, QS tile, lock countdown, pens UI |

Reason for 17+ modules (above the research's ~12): feature modules = one owner per parallel agent, per-module
strings files (no strings.xml merge conflicts), JVM cores enforce "no Android in the logic shared with Mac".
Optional `:systemui` (chunk 28) for the LSPosed/root path.

### 2.3 DI and state ownership
- `AppGraph` (in `:app`, built in `Application.onCreate` with **no I/O on main**: every store is `lazy` and loads on
  `Dispatchers.IO` behind a `suspend fun ready()`); one app `CoroutineScope(SupervisorJob()+Default)`; injected
  `DispatcherProvider` + `Clock`. Each chunk owns one `XxxModule` class in its module that `AppGraph` composes (A4: only
  chunk 02 edits `AppGraph` structure); feature modules declare `XxxDeps` interfaces; ViewModels
  via `viewModelFactory { initializer { … } }`. No service locator calls outside `:app`.
- **One glucose state source**: `GlucoseHub` (`:monitor`) exposes `StateFlow<GlucoseState>` = latest reading + recent
  window, sensor state (warm-up/ended/missing core/BT off), source status, alert/mute state, role, link state. Fed by
  exactly one input per role: wearer = `:sensor` SourceManager (Libre 3 direct or Juggluco); follower = `:share`
  FollowerEngine. Consumers: UI VMs, widgets, live notification, alert runtime, owner publisher, uploads, bridge.
- Concurrency: each engine owns its mutable state on a `limitedParallelism(1)` dispatcher (actor style); no
  `@Synchronized` maps; BLE callbacks → `callbackFlow`/`Channel`; all suspend APIs; one-shot UI events modelled as state.
- Error policy: no bare `runCatching` on I/O; corrupt files quarantined + surfaced as a visible state; a failed
  identity decrypt is a **blocking error state**, never onboarding.

### 2.4 Services and reliability
- `MonitoringService` (`connectedDevice` FGS, START_STICKY) hosts the role engine; started from MainActivity, boot,
  package-replaced, HealthCheck. FGS-start denial → visible "Monitoring stopped — tap to resume" notification + home banner.
- Alerts are evaluated in-process by the alert runtime on every hub update **and** by `AlertCheckReceiver` (exact
  alarm, single-shot `setExactAndAllowWhileIdle`, rescheduled each fire/boot/time change) using persisted last-reading
  time — works with the service dead (fixes "no alerts when the service is dead"). HealthCheck (every 15 min exact alarm)
  restarts the FGS.
- `BluetoothAdapter.ACTION_STATE_CHANGED` receiver → GattLink/BLE share reopen (fixes "never recovers after BT off").
- GATT operation timeout (10 s) + per-driver no-data watchdog.
- Alert reliability row on home + settings page (notifications, channel importance, exact alarm, FSI, battery
  optimisation, DND access, BT) with deep links (P1 page, P0 row for the missing-permission banner).

### 2.5 Storage and first run (no migration — owner decision)
- V1 owns its formats: settings in Preferences DataStore under the schema keys; engine state (identity, roster,
  epochs, follower link, upload cursors) in typed DataStore files with secrets encrypted by SecureStore; history in Room
  (`SukraraDb` v1, `exportSchema = true`, schema JSON committed, future migrations tested with `MigrationTestHelper`).
  Every persisted format has a version field from day one. No PoC file is read; nothing from the PoC is migrated.
- `allowBackup=false` + `dataExtractionRules` excluding everything (secrets and health data never leave the device).
- Corrupt file → quarantined + visible error state; failed identity decrypt → blocking "Identity can't be read" screen
  (restore from recovery code / start fresh with explicit confirmation), never a silent onboarding.
- **First-run setup** (chunk 24, P0) per D1-CHANGE.md: welcome → role → identity (restore from recovery code = the
  showcase's ncryptsec/nsec; keeps followers) or start fresh → permissions checklist → sensor (chunk 18 flow) → alert
  thresholds review → home; every step skippable to a "Finish setup" home card. Mac: welcome → follow → notifications
  → login item (chunk 23).
- **Side by side with the PoC**: two packages, two launcher icons; only one may own the Libre 3 sensor (new-sensor flow
  warns, takeover over NFC; if it fails keep the PoC until the next sensor). assetlinks gets the V1 entry; both apps
  claim `https://sukrara.mauri.app/join*` and `sukrara://` → Android chooser while both are installed.

### 2.6 Notifications
Channel registry (single object in `:monitor`, chunk 10) with clean V1 ids (`glucose`, `status`, `requests`, `sensor`,
`sensor.countdown`, `pen.results`, `pen.check`, `alert.<kind>` / `alert_alarm.<kind>` for the 8 kinds,
groups `alerts`, `alerts_alarm`) and a version-tagged deletion list for future renames. One builder per kind; alert
notifications keep the 0.11.53 behaviour (never grouped/autogrouped: own group key per alert); notification ids in one
`NotificationIds` object.
Live Update (promoted ongoing, `setShortCriticalText`) for the glucose notification.

### 2.7 Widgets, settings schema
- Glance widgets render through `:core:graph` (`CardSurface.Widget*`), per-widget settings in DataStore keyed by
  appWidgetId. WidgetProbe dropped (size from Glance `LocalSize`).
- Settings: `contracts/settings/settings.json` is rewritten with clean V1 store + key names per platform (no PoC
  names; adds calendar, Health Connect); gen.py emits `SettingsSpec`; every number setting uses `NumberDialog` with
  the spec; SettingsSchemaTest checks schema ↔ code keys.

### 2.8 Tooling and tests
- Lint (curated errors + baseline), Spotless/ktlint (+compose-rules), `./gradlew check` is the one command.
- Unit tests JUnit4 (+Robolectric where Android needed), `kotlinx-coroutines-test` virtual time for alert timelines.
- Contract tests per module (§4). Roborazzi goldens (synthetic data) for the card on every surface, home, alert
  settings, new-sensor states, widgets — light/dark/es/font 1.3/2.0.
- Release smoke: `assembleRelease` + `verify-apk.sh` + install on emulator (chunk 27a).

---
## 3. Mac V1 architecture (`apps/macos`)
- `Package.swift`, swift-tools 6.2, **Swift 6 language mode**, `.macOS(.v15)` (widget already needed 15; owner on 27),
  arm64. Dependency: `nostr-sdk-swift` exact 0.45.1.
- Targets:
  - `SukraraCore` (nonisolated, extension-safe): models, units, Durations, Clock, SettingsSpec (generated),
    WearerSettings, CSV export, treatment merge, App Group snapshot codec, stores (`ReadingsStore`,
    `TreatmentStore`, `KeychainStore` service `app.mauri.sukrara` — V1 formats, versioned, under
    `~/Library/Application Support/app.mauri.sukrara/`).
  - `SukraraGraph` (extension-safe): the one renderer, SukraraGraph.swift split into Model/Stats/Freshness/Layout/
    Chart/Ring/Badges/CardSurface files.
  - `SukraraAlerts` (pure): engine, rules, MuteCopy, episodes codec (`alertEpisodes` / `alertSettings` keys kept).
  - `SukraraShare`: protocol v2 (wraps NostrSDK), invites/QR, BLE framing; `RelayPool` **actor** behind
    `RelayTransport` protocol; `NearbyCentral` actor (CoreBluetooth, delegate on its own queue → AsyncStream);
    `FollowerSession` state machine (follower keys in the Keychain, V1 format).
  - `SukraraApp` (library, `.defaultIsolation(MainActor.self)`, `@Observable AppModel`): NSStatusItem + NSPopover/panel
    hosting SwiftUI, windows (first-run, Settings panes, Treatments, Add treatment, Report, Profile/Follow/Pairing),
    notifier (UN, clean category ids), sounds (copy `.caf` to ~/Library/Sounds with prefix `app.mauri.sukrara.`
    before use), login item, update check, `LaunchMode` (pure: normal | snapshot(DEBUG) | test → services plan;
    unit-tested; replaces LaunchGuard source-grep).
  - `Sukrara` (executable): 10-line `main.swift` → `LaunchMode.parse` → `AppDelegate`.
  - `SukraraWidget` (executable built with `-application-extension`, `-e _NSExtensionMain`; bundle script wraps it as
    `SukraraWidget.appex` (bundle id `app.mauri.sukrara.widget`); kinds `app.mauri.sukrara.widget.{value,graph,combined}`; reads App Group snapshot only;
    timeline with staleness entries at +5/+20 min; app reloads only on value change, throttled).
  - `sukrara-snap` (executable, DEBUG): offline snapshot renderer for every window/surface (replaces the 12 `-*Snapshot` flags).
  - Tests (Swift Testing): `CoreTests`, `GraphTests`, `AlertsTests`, `ShareTests` (vectors + fake relay — never real
    relays), `AppTests` (LaunchMode, first-run state, menubar title). Fixtures via `Contracts.url("…")` resolving
    `SUKRARA_CONTRACTS` env or `#filePath`-walk to `contracts/` (test-only).
- No debug entry points in release: `-debugsend`, snapshots, self-tests are `#if DEBUG` / test targets only.
- Ids/storage (no migration): bundle `app.mauri.sukrara`, Team `QWUV9H4A3G`, App Group
  `QWUV9H4A3G.app.mauri.sukrara`, defaults keys from the schema, Keychain service `app.mauri.sukrara`, App Support
  `app.mauri.sukrara/`, URL scheme `sukrara` (install.sh moves the PoC bundle to "Sukrara Showcase.app" and
  `lsregister -f`s V1 so V1 handles `sukrara:`). No GlucoseBar/PoC migration code. Logs → `os.Logger` (private by
  default) + bounded file log (1 MB × 2) in `~/Library/Logs/app.mauri.sukrara/`.
- Bundle/sign: `Bundle/bundle.sh` (version from `VERSION`; CFBundleVersion `90000`, dev `90000.<yyMMddHHmm>`), sign inside-out
  with the Apple Development identity of team QWUV9H4A3G (as 0.11.53), hardened runtime; `Bundle/dmg.sh` (dmgbuild
  layout reused); notarization only if `NOTARY_PROFILE` exists (it does not tonight → not notarized, as 0.11.x).
- Concurrency: CB delegates/URLSession in actors; UI `@MainActor`; no `assumeIsolated`, no `nonisolated(unsafe)`
  except documented constants; `swift format lint --strict` in CI.

---
## 4. `contracts/`
```
contracts/README.md          what each file is, who reads it, how to change it (both impls in the same change)
contracts/alerts/            SPEC.md, scenarios/*.txt (unchanged expectations), copy.json, sounds/{mac/*.caf, android/raw/*.ogg}
contracts/graph/             layout/{scenes,rules,expect}.json, freshness/, labels/, sensor/, clock/, share/, mark/, place/,
                             surfaces/surfaces.json (paths → V1 files)
contracts/settings/          settings.json (real store names, `only` + reason), durations.json
contracts/protocol/          PROTOCOL-v2.md, PROTOCOL-identity.md, identity/{profile-v1.json,vectors.json}, ogs/ogs-draft1.json,
                             sync/*.json (NEW: owner→follower event sequences → expected follower state)
contracts/treatments/        treatment-form.json, libreview-fixture.json, SPEC.md (libreview/SPEC.md)
contracts/export/            export fixtures (CSV, 23 columns)
contracts/pens/              scenarios.json (novopen)
contracts/sensor/traces/     Libre 3 / sensor BLE traces (from sensors-android)
```
- Formats: JSON/TXT/MD only (+ sound assets); every JSON has `"v"`; no app paths except surfaces.json (checked).
- Consumption: Android tests via `Contracts.file("alerts/scenarios")` (`sukrara.contracts` system property from the
  `sukrara.contracts` convention plugin; Gradle `inputs.dir`); Android sounds via `res.srcDir(contracts/alerts/sounds/android)`.
  Mac tests via `Contracts.url(…)`; bundle.sh copies `contracts/alerts/sounds/mac/*.caf`.
- Parity: check `parity` (contracts change ⇒ both impls or `Parity-skip`), CI path filter (contracts ⇒ both CIs),
  scenario expectations immutable without owner OK (CLAUDE.md), `-settingsselftest` replaced by `SettingsSchemaTests` on both.

---
## 5. Priorities (re-tiered per CHANGES.md A1; orchestrator re-tiers again at 06:30)
**P0 — must work in 0.90.0 (owner: Android wearer, Libre 3 direct; Mac follower via Nostr v2 + nearby BLE)**
- First-run setup (Android 24, Mac 23) in the safe order: role (confirmed) → permissions checklist (= the reliability
  row: notifications, full-screen intent, exact alarm, battery exemption, DND override / channel importance,
  Bluetooth) → "Turn off Sukrara Showcase" → sensor → alert thresholds explicitly confirmed → identity restore
  **gated** (live V1 reading + showcase uninstalled or sharing off) or "Start fresh" (confirmed). Mac: follow
  (link/QR/nearby) → notifications → login item.
- Libre 3 direct with the **LibreView account receiver ID** (sign-in for the ID only; start uses it, takeover tries it
  then V1's own) so sensors can move between V1, the showcase and Abbott; BLE reconnect; Juggluco fallback.
- GlucoseHub + MonitoringService + BT recovery + health check; "Not monitoring: no sensor yet" before the first
  reading (no no-data alarm until monitoring started); Live Update chip (status-bar substitute) is an acceptance item.
- Alerts (engine + scenarios, notifier, sounds, Mute/Remind less/Unmute/Change time, Mute all, no-data alarm
  independent of the FGS) on both platforms; no double alarms during the switch (setup step + home banner while the
  showcase package is installed; install.sh mac quits the PoC and removes its login item).
- Sensor-end warning (end time on card/home, one notification a few hours before, "Sensor ended → Pair new sensor").
- Card/graph renderer on home + live notification (tap → quick Add treatment); Mac menubar + panel.
- Wearer→follower sharing over relays with identity-conflict blocking state; Mac follower (also follows a 0.11.53
  wearer — protocol compat test).
- Treatments: log/edit/delete/undo, marks, sync, QuickTreatmentActivity. Settings (shrunk to daily use): alerts,
  ranges/units, graph basics, About + update check (prerelease rule); Mac: Alerts+ranges, General, treatments windows.
- Release hygiene: no debug in release, native core guard, new release key verified, en+es strings.
**P1 — free slots / 0.90.x follow-up RCs**
- Home widgets Android (20, tap → quick Add treatment) and Mac widgets (22); Android follower role + BLE nearby
  share (25); uploads Nightscout/Tidepool/LibreView upload, Health Connect/broadcasts/local servers (Garmin) (26);
  pens (NovoPen NFC); full sensor reminders page + calendar; backup page; CSV export; report card + Mac report;
  profile editor; settings transfer; advanced/diagnostic pages; Mac rings polish.
**P2 / cut → GitHub issues (with reason)**
- Root status bar + LSPosed lock-screen widget + Restart-System-UI card (chunk 28 cut: root-only, untestable tonight;
  Live Update chip covers the glance; restore later as optional module).
- Untested drivers Dexcom G6/G7/Stelo, Aidex X, SmartGuide + "Sensor scan: other sensors" + "Untested sensor banner":
  no hardware, untested, ~1k LOC; owner uses Libre 3.
- Mac Juggluco-LAN source + subnet discovery, Mac Nostr v1 `cgmnostr:` link: unused by the owner (defaults checked),
  no UI, plaintext secret.
- WidgetProbe, battery-lab knobs (`connPriority`, `screenOffSkip`, `nostrInterval`, `libreviewInterval`), Libre 3
  experiment prefs.
- Importing PoC data/settings (owner: no backwards compatibility). Settings search; CompanionDeviceManager presence;
  Sparkle + notarization (no Developer ID); KMP (D3); UX ideas in OPS/issues/research-ux.md.

---
## 6. Chunk DAG (rolling slots, ≤ 8 concurrent) — copy of `dag-v2.md` (authoritative)
"pre" = may start G0 + pure code before its dependency merges, then rebases before G1 tests. "iface" = code against
the interface from 02a / `:core:model`, don't wait for the merge. Scaffolds (A2): 01a/02a/03a = minimal skeleton
committed within ~30 min, heartbeat `MILESTONE <NN>a <sha>`, merged by the orchestrator at once; 01b/02b/03b continue
on the same branch (02a proves KSP + Room compile under AGP 9.1.1).

| # | id | P | eff | G0 critic | depends on |
|---|---|---|---|---|---|
| 01a/01b | foundation | P0 | H | – | r0 (01a skeleton ~30 min → MILESTONE) |
| 02a/02b | android-scaffold | P0 | H | – | r0 (02a: modules + core:model GlucoseState/GlucoseHub + KSP/Room compile proof) |
| 03a/03b | mac-scaffold | P0 | H | – | r0 |
| 04 | android-alerts-core | P0 | H | yes | 01a, 02a (pre) |
| 05 | mac-alerts-core | P0 | H | yes | 01a, 03a (pre) |
| 06 | android-protocol | P0 | H | yes | 01a, 02a (pre) |
| 07 | mac-protocol | P0 | H | yes | 01a, 03a (pre) |
| 08 | android-graph-designsystem | P0 | M | self | 01a, 02a (pre) |
| 09 | android-data | P0 | M | self | 01a, 02a |
| 10 | android-monitor | P0 | H | yes | 02a; 09 iface |
| 11 | android-alerts-runtime | P0 | H | yes | 04; 09, 10 iface |
| 12 | android-sensor-libre3 (+ LibreView receiver-ID client) | P0 | H | yes | 02a; 09 iface |
| 13 | android-share-owner (+ identity-conflict state) | P0 | H | yes | 06; 09 iface |
| 14 | mac-core-graph | P0 | M | self | 01a, 03a |
| 15 | mac-follower-runtime | P0 | H | yes | 07; 14 iface |
| 16 | android-home-ui | P0 | M | self | 08; 09, 10 iface |
| 17 | android-settings-ui (shrunk) | P0 | M | self | 08; 09, 11 iface |
| 18 | android-sensor-ui (+ LibreView sign-in, sensor-end warning) | P0 | H | yes | 08, 12 |
| 19 | android-treatments (+ pens P1 part) | P0 | M | self | 08, 09; 13 iface |
| 21 | mac-shell-alerts | P0 | H | yes | 05, 14; 15 iface |
| 23 | mac-windows (shrunk) | P0 | M | self | 14; 15 iface |
| 24 | android-setup-sharing | P0 | M-H | yes | 08; 09, 13, 18 iface |
| 27a | android-release-smoke | P0 | H | yes | 01b, 02b, 12 (start ~06:30) |
| 27b | android-first-run-walk | P0 | H | yes | 11, 16, 18, 24 merged (~07:15) |
| 20 | android-widgets | P1 | M | self | 08; 10 iface; 19 (quick Add treatment) |
| 22 | mac-widget | P1 | M | self | 14, 15 |
| 25 | android-share-follower-ble | P1 | H | yes | 06, 13 |
| 26 | android-integrations (uploads etc.; LibreView *upload* only) | P1 | M | self | 09; 12 (LibreView client) |
| 28 | android-systemui | CUT → issue | | | |
Orchestrator re-tiers at 06:30. UX review batched per wave by the orchestrator (not inside chunks).
Roborazzi matrix: light+dark × default+largest font, one phone size.
DI (A4): each chunk owns one `XxxModule` class in its module; only 02 edits `AppGraph` structure.

Conflict hotspots: `settings.gradle.kts` + `AppGraph` structure (02 only), manifest (per module; `:app` aggregates),
FEATURES.md (each chunk edits only its sections), `allow.json` (append-only).

---
## 7. Gates
- **G0 inside a chunk** (≤ 15 min): research (official docs + issues), write `OPS/plan/<NN-id>.md` (≤ 80 lines:
  design, files, tests, risks, deviations from brief) + a self-critique pass. **Critic subagent mandatory** only for
  safety chunks (DAG "G0 critic = yes": alerts 04/05/11, sensor 12/18, protocol/share 06/07/13/15, monitor 10, setup
  24, release 27a/27b): prompt = brief + plan, "find what's wrong, missing, risky, over-engineered; approve or list
  blockers" → revise once; its second answer is final.
- **G1**: build in worktree `v1-<NN-id>`, small commits, `./gradlew check` (Android) / `swift build && swift test`
  (Mac) / `tools/check/run.sh` green.
- **G2 code review**: fresh agent receives only `git diff next...HEAD`, the brief's acceptance list and this
  checklist: CLAUDE.md rules (headers, strings en+es, secrets, cleartext allow-list, debug compiled out, one graph,
  settings schema, alert scenarios unchanged, FEATURES/README), clean V1 ids only (no `.poc`/`dev.spike`/PoC keys), versioned formats,
  no I/O on main, cancellation/timeouts, error policy (no swallowed I/O errors), tests meaningful (not tautological),
  no dead code / PoC investigation comments, names, file size < 400 LOC where sensible. Findings fixed or answered in
  `OPS/reviews/<NN-id>.md`.
- **G2 UX review — batched by the orchestrator per wave** (A8): one UX reviewer over the merged wave's Roborazzi
  goldens (matrix: light+dark × default+largest font, one phone size; es spot checks) / emulator screenshots
  (synthetic data) / `sukrara-snap` PNGs; applies OPS/research/ux.md §2 items 1–30 that apply, yes/no per item;
  findings → fix tasks for the owning chunk or the final fix round.
- **Final whole-app reviews (07:30–08:15, parallel, on `next`)**: (1) code/architecture, (2) security/privacy
  (exported components, secrets at rest, logs w/o health data, network allow-list, debug in release — inspect the
  release APK/app), (3) UX/usability/utility (screens vs ux.md), (4) accessibility (TalkBack/VoiceOver labels, value
  sentence, font scale, contrast), (5) performance/battery (wake locks, alarms, redraw dedupe, main-thread I/O, widget
  reload budget). Each writes `OPS/reviews/final-<n>.md`; blockers → fix agents (≤ 4) until 08:15.
- **Feature test plan** (agent "feature-pass" walks FEATURES.md; result table `OPS/reviews/feature-pass.md`):
  | FEATURES.md section | verified by |
  |---|---|
  | Alerts | AlertScenarioTest (Android) + AlertsTests (Mac) over contracts; notifier Robolectric test; emulator: synthetic low → notification + actions (debug build); Mac: debug run, UN notification |
  | Treatments | unit (merge, form fixture, sync payload); emulator add/edit/undo; Mac window snapshot + unit |
  | Mac menu and windows | `sukrara-snap` PNGs + Mac run (offline LaunchMode) |
  | Readings and backfill | unit (follower sync fixtures contracts/protocol/sync, DB); emulator synthetic |
  | Graph and value card / One card / Freshness | CardLayout/GraphFixture/Freshness tests both platforms + Roborazzi + snap |
  | Sensor life / New sensor | unit (traces, NFC parsing, PairingStore compat), Roborazzi of flow states; **deferred-device** (real NFC/BLE) |
  | Untested sensor banner / Sensor scan: other sensors | cut (P2) — FEATURES.md moved to "Not in V1" |
  | Share-with-clinic / Add-widget banner / Closed tips | Roborazzi + unit (one banner slot) |
  | Restart-System-UI card / Root status bar / Lock screen widget | **deferred-device** (root) or cut with chunk 28 |
  | Data export | export fixture test both platforms |
  | Settings | SettingsSchemaTest both; Roborazzi pages; mirror unit test |
  | Live notification | Robolectric notification test + emulator screenshot |
  | Sensor reminders / Sensor end in calendar | P0-lite warning: SensorEndPlan tests + Robolectric notification; full page/calendar P1 |
  | Sharing over Bluetooth | unit (framing vectors, HELLO); **deferred-device** (two radios) |
  | Share with apps on this phone / Nightscout / Tidepool | unit (FakeNightscout, queues, payloads); no live endpoints |
  | Insulin pens | PenScenarioTest over contracts/pens; **deferred-device** (NFC pen) |
  | Languages | StringsParity test + es Roborazzi |
  | First-run setup (new section) | Robolectric VM tests per step + Roborazzi of every step + emulator walk (chunk 27b); Mac snap + run |
  | Switching (protocol compat) | V1 Mac follows a 0.11.53 wearer (fixtures from the PoC in contracts/protocol/sync) + V1 follower handles the rotation after restore |
  | Side by side with the showcase | chunk 27a/27b: PoC debug + V1 on one emulator, both run, join link → chooser, sensor-ownership warning shown |

---
## 8. Release plan and docs
- Version `0.90.0` (VERSION), Android versionCode 90000, Mac CFBundleShortVersionString 0.90.0 / CFBundleVersion
  90000. Tag `v0.90.0`, title "Sukrara 0.90.0 (V1 preview)", **GitHub prerelease**. Assets: `Sukrara-0.90.0.apk`
  (signed with the new release key), `Sukrara-0.90.0.dmg`, `Sukrara-mac-0.90.0.zip`. Release notes from
  CHANGELOG.md: what V1 is, "Switching from the showcase" (below), known untested-on-device list.
- Release title/notes say **"release candidate — untested on a real sensor"**.
- **Switching from the showcase** (README + morning report; numbered, order matters; 27b writes the final text):
  1. Mac: install V1 (install.sh quits the PoC app, removes its login item, keeps it as "Sukrara Showcase.app") and
     follow the showcase's invite — works today, risk-free.
  2. Android: install V1 next to the showcase; setup: role → permissions checklist → turn off the showcase's alerts and
     sensor source → take over the sensor over NFC with your LibreView sign-in (receiver ID) → confirm readings arrive.
  3. Only then restore your identity from the showcase's recovery code (Backup page) — the showcase stops sharing.
  4. Check the Mac shows V1 data < 5 min old.
  5. Uninstall (or fully turn off) the showcase; join links stop showing a chooser.
  6. Remove the old Mac follower entry from the follower list.
  Rollback: reopen the showcase and take the sensor back over NFC (possible because V1 starts/takes over with the
  LibreView account receiver ID). Nothing is copied between the apps.
- **Owner first-hour walk** (release notes, 10 min): reliability row all green; a reading < 5 min old on home, the
  notification/Live Update chip and the Mac; Mac shows the same value; Mute → Unmute from the lock screen works; a
  test alert sounds through DND; the showcase is silenced/removed; recovery code + release key backed up; rollback
  steps understood.
- **"Not in 0.90.0 — keep the showcase for now if you rely on these"** table (generated from FEATURES "Not in V1" at
  release time; 27b drafts it): root status bar icons, LSPosed lock-screen widget, Restart-System-UI card, untested
  sensor drivers + other-sensor scan, plus every P1 that missed the cut (likely: home/Mac widgets, Android follower
  role + Bluetooth share, Nightscout/Tidepool/LibreView upload, Health Connect/broadcasts, Garmin via local xDrip+
  server :17580 (only one app can bind it), pens, full reminders + calendar, CSV export/report, settings transfer).
  Also: two LSPosed modules must not hook System UI at once.
- Keys: `~/.android/sukrara-release.jks` + Keychain password, made by chunk 01; docs/20-publishing.md "Back up the
  release key" (copy both into the password manager now; losing them = no more updates for installed apps).
  assetlinks: V1 entry (release + debug cert) added next to the poc entry (site deploys on the next→main merge).
- Update channel: both apps call `GET /repos/mauriciabad/Sukrara/releases` (not `/latest`), pick the newest
  non-draft release whose version > installed; prereleases included iff the installed build is a prerelease
  (`0.x`). Homebrew: workflow already skips prereleases — untouched. Obtainium users: notes say "enable prereleases".
- Release steps (orchestrator, ~08:15): `tools/check/run.sh` on next; `touch tools/check/v1-complete`; `tools/release/
  android-release.sh` (assembleRelease + verify-apk: signer = android-cert.sha256, versionCode, native core, no
  debug classes in dex) and `mac-release.sh` (bundle + dmg + `codesign --verify --deep --strict`, TeamIdentifier,
  appex present, no DEBUG symbols like `debugsend`); `gh release create v0.90.0 --prerelease …`; merge next→main;
  README/docs links updated in the same merge.
- The 07:30 ship decision lists which P1 items made it (fills the table above).
- Docs: README (V1 features, install, switching from the showcase, layout), FEATURES.md (per chunk + "First-run
  setup" + "Not in V1"), CLAUDE.md (V1, chunk 01), docs/ARCHITECTURE.md (from §2–4, chunk 01 skeleton + each chunk its
  part), docs/DEVELOPMENT.md (from NEXT.md §5), docs/20-publishing.md (keystore + backup), docs/10-showcase.md "V1
  port" section listing every P2 cut with reason (final docs agent), CHANGELOG.md.

---
## 9. Risks and mitigations
| risk | mitigation |
|---|---|
| Libre 3 driver/GattLink regression, untestable tonight | port the proven state machine with minimal semantic change (shell refactor only), trace replay tests, Juggluco fallback; the showcase stays installed as the fallback |
| V1 can't take over the PoC-owned sensor | LibreView account receiver ID (P0) as the PoC uses; warn + steps in the new-sensor flow; else keep the showcase for this sensor |
| Restore cuts the showcase off from followers too early | restore gated behind a live V1 reading + showcase off; identity-conflict blocking state in 13 |
| Restore from recovery code fails → followers lost | contract vectors (identity/ncryptsec) on both platforms; start-fresh path + re-invite documented |
| Release key lost | made once by chunk 01, backup steps in docs/20 + morning report action item |
| Alerts silent (service dead, permissions) | alarm-driven evaluation independent of FGS, health check, permissions checklist in setup + reliability row, scenario tests both platforms |
| Two apps alerting at once during the switch | README: turn off showcase alerts / stop its source when V1 is set up; V1 setup shows the same note |
| Too many chunks for the time | rolling slots, re-tier at 06:30, widgets/25/26 only on free slots, 28 cut; per-platform ship decision at 07:30 |
| Merge conflicts in shared files | ownership table §6, per-module strings/manifests, append-only allow.json |
| R8 strict full mode breaks JNA/UniFFI/serialization/Room | keep rules ported, release smoke on emulator (27a) |
| Mac Swift 6 strictness slows porting | actors only at BLE/relay boundary; UI MainActor default; `@preconcurrency import` where SDK lacks Sendable |
| Checks block early commits (V1 files missing) | "pending V1 file" NOTE until `v1-complete` |
| Real relays/sensors touched by tests | RelayTransport fake, LaunchMode offline, CI has no network-dependent tests |

**Fallback (07:30 decision, per platform):** a platform ships in 0.90.0 only if its P0 gate is green: Android = unit
+ contract tests, 27a release smoke (new key, beside the PoC) + 27b first-run walk, alerts runtime test,
Libre 3 trace tests; Mac = swift test, signed bundle with appex, first-run + follow snapshot review. Minimum 0.90.0 =
whichever platform passes (notes say "keep using the showcase on <platform>"). Because V1 installs side by side,
shipping is low-risk for the owner: the showcase keeps working untouched. If neither passes: no release; `next` keeps
V1 work, morning report lists the gaps. P1 chunks that miss the cut are left out with their features under "Not in V1
yet" (Android follower role without chunk 25: the role choice shows "Following on Android comes in 0.90.x — use the
showcase", never a broken engine).

## Challenges to orchestrator decisions
- None blocking. Note: dropping migration means a new Credential-Manager/password-manager entry and new Nostr device
  key on restore; the recovery-code restore is therefore P0 (gated, see §5), or every follower must re-join.
