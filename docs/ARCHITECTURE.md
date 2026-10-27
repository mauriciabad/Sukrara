# Sukrara V1 architecture

## Mac (`apps/macos`)

One SwiftPM package (swift-tools 6.2, Swift 6 language mode, macOS 15, arm64). Build, test and bundle commands:
[`apps/macos/README.md`](../apps/macos/README.md).

### Targets

```
Sukrara (exe) ─► SukraraApp ─► SukraraCore, SukraraGraph, SukraraAlerts, SukraraShare ─► NostrSDK 0.45.1
SukraraWidget (exe, appex) ─► SukraraCore, SukraraGraph          (extension-safe only)
sukrara-snap (exe, debug) ─► SukraraApp
Tests: CoreTests GraphTests AlertsTests ShareTests AppTests ─► SukraraTesting (fakes, Contracts.url)
```

- `SukraraCore`, `SukraraGraph`: nonisolated, compiled with `-application-extension` so the widget can link them.
- `SukraraApp`, `Sukrara`, `sukrara-snap`, `AppTests`: `.defaultIsolation(MainActor.self)` +
  `NonisolatedNonsendingByDefault` + `InferIsolatedConformances`. Background work goes into actors / `@concurrent`.
- `SukraraAlerts` is pure (no UI, no I/O); `SukraraShare` keeps every transport behind a protocol.

### The widget extension: built by SwiftPM, wrapped by `Bundle/bundle.sh`

Decision (chunk 03, within the first 20 minutes): **SwiftPM executable target + bundle script**, no Xcode project.
`SukraraWidget` is linked with `-application-extension -e _NSExtensionMain -u _main`: Xcode links extensions with
Foundation's `_NSExtensionMain` entry point (the Swift `main` traps in ExtensionFoundation, docs/13), and `-u _main`
keeps the `@main` WidgetBundle, which nothing else references, from being dead-stripped in release builds
(xtool-org/xtool#313). Checked on the release binary: `LC_MAIN` points at the `_NSExtensionMain` stub, the widget's
code and kinds are present, `pluginkit -a` registers `app.mauri.sukrara.widget` and chronod adds it ("Added
extensions: … app.mauri.sukrara.widget, version: 90000"). chronod purges dev copies outside an Applications folder,
so the live widget gallery is checked after `tools/install.sh mac`. Fallback if it ever stops loading: the PoC's
proven raw `swiftc` command (poc/spikes/menubar/bundle.sh), same flags.

`Bundle/bundle.sh` assembles `build/Sukrara.app` (+ `Contents/PlugIns/SukraraWidget.appex`) from
`Bundle/Info.plist.in`, `Bundle/Widget-Info.plist.in` and the entitlement templates, version from `<repo>/VERSION`
(`CFBundleVersion` = versionCode, `--dev` appends `.<yyMMddHHmm>`), and signs inside-out with the Apple Development
identity of team QWUV9H4A3G (hardened runtime; the app is not sandboxed, the widget is; both share the App Group).
`--unsigned` signs ad hoc for CI. `Bundle/dmg.sh` makes the dmg and zip.

### Ids and paths (V1, no migration from the PoC)

All in `SukraraCore` (`SukraraIDs`, `SukraraPaths`): bundle `app.mauri.sukrara`, widget `app.mauri.sukrara.widget`,
App Group `QWUV9H4A3G.app.mauri.sukrara` (Info key `SukraraAppGroup`), Keychain service `app.mauri.sukrara`, defaults =
the standard domain with schema keys, URL scheme `sukrara`, widget kinds `app.mauri.sukrara.widget.{value,graph,combined}`,
`~/Library/Application Support/app.mauri.sukrara/`, `~/Library/Logs/app.mauri.sukrara/` (1 MB × 2),
`~/Library/Sounds/app.mauri.sukrara.<name>.caf`, App Group snapshot `glucose.json` (path only from
`SukraraPaths.appGroupSnapshot()`, i.e. the system's group container).

### Launch gate (replaces the PoC's LaunchGuard source grep)

`SukraraApp/Launch/LaunchMode.swift`, pure and covered by `AppTests/LaunchModeTests`:

- `LaunchPlan.parse(args, build:, env:, home:, exists:) throws(LaunchError) -> LaunchPlan` (mode + services + data).
  Arguments other than `-psn_…` → exit 2. A dev build (debug build, bare executable, or a bundle inside a git
  checkout) → exit 3 unless `SUKRARA_DEV=1`; with it: `ServicesPlan.none` and `DataScope.isolated` (separate
  home `SUKRARA_DEV_HOME` or `…/Application Support/app.mauri.sukrara.dev`, Keychain service and defaults suite
  `app.mauri.sukrara.dev`). A refused launch writes stderr + os_log and, when started by LaunchServices, shows an
  alert (never a silent exit).
- `LaunchPlan.snapshot(request, build:, scratch:)`: only for `sukrara-snap` (the only snapshot path), debug only,
  no services, isolated data.
- `ServicesPlan` is an **upper limit**: the app ANDs it with the user's settings (`limited(by:)`) and starts services
  only through `ServicesPlan.start(using: some ServiceStarting)`. The app's `ServiceStarting` is the only code that
  creates a Bluetooth central, relay pool, alert engine, widget feed or notification request.

### Mac interfaces (what later chunks implement)

```swift
// SukraraCore
public struct Reading: Sendable, Codable, Hashable { var t: Date; var mgdl: Int; var rate: Double? } // rate mg/dL/min; JSON t = epoch ms
public protocol Clock: Sendable { var now: Date { get } }                       // SystemClock; FakeClock in tests
public protocol ReadingsStoring: Sendable { func load(since: Date) async throws -> [Reading]; func merge(_: [Reading]) async throws }
public protocol TreatmentStoring: Sendable { func load() async throws -> TreatmentLog; func save(_: TreatmentLog) async throws }
public protocol SecretStoring: Sendable { func read(account:) throws -> Data?; func write(_:account:) throws; func delete(account:) throws }
public protocol SettingsStoring: Sendable { func value<V>(_: SettingSpec<V>) -> V; func set<V>(_: V, for: SettingSpec<V>) }
public struct SettingSpec<Value: SettingValue> { let key: String; let defaultValue: Value } // gen.py emits instances
public struct GlucoseSnapshot: Codable { var v: Int; var readings: [Reading] }   // placeholder, chunk 14 owns it
public struct TreatmentLog: Codable { var v: Int }                              // placeholder, treatments chunk owns it
// SukraraShare
public struct SignedEvent; public struct EventFilter { func matches(_:) -> Bool }
public protocol RelayTransport: Sendable { func connect(_: [URL]) async; func publish(_: SignedEvent) async throws
    func subscribe(_: EventFilter) -> AsyncThrowingStream<SignedEvent, Error>; func close() async }  // FakeRelay in tests
public enum LinkState { case notLinked }                                        // chunk 15 adds states
// SukraraAlerts
public struct AlertUIState { var isAlerting: Bool }                             // chunk 05 replaces
// SukraraGraph
public enum GlucoseCard { static let noValueText = "—"; static func face(_: GlucoseSnapshot?) -> String } // chunk 14: renderer + freshness
// SukraraApp
@Observable public final class AppModel { let plan: LaunchPlan; var glucose: GlucoseSnapshot?; var alerts: AlertUIState
    var link: LinkState; var statusTitle: String; var notMonitoringReason: String? }
```

Test fixtures: `Contracts.url("alerts/scenarios")` (`SukraraTesting`) resolves `SUKRARA_CONTRACTS` or walks up from
the test source to `contracts/`.

## Mac alerts (`apps/macos/Sources/SukraraAlerts`)

The alert rules of [`contracts/alerts/SPEC.md`](../contracts/alerts/SPEC.md) as a pure Swift 6 library (functional
core); the app shell (chunk 21) owns notifications, sounds, timers and storage.

- `AlertEngine` — a `Sendable` value type: `mutating func handle(_ input: AlertInput, at: Date, using: inout RNG) ->
  [AlertEffect]`. No I/O, no clock, no globals; firing ids come from the passed generator (the convenience overload
  uses the system one).
  - Inputs: `reading`, `treatments` (live carbs / rapid insulin), `sensor` (`SensorWindow`), `settings`, `scope`
    (`all` / `noDataOnly` for a follower that mirrors the wearer), `tick`, and this device's actions `mute`,
    `remindLess`, `unmute`, `remindAsUsual`, `muteAll`, `unmuteAll`. Every input re-evaluates at once.
  - Effects, in order: `fire` (post, replacing the group's notification), `clear`, `back(side)` (margin end, history),
    `resume` (after restore: show again silently, same id / n, never republish), and always last `schedule(Date?)`
    (the next `tick`). The shell should also send `tick` on wake from sleep (`NSWorkspace.didWakeNotification`).
- `AlertEngine.Snapshot` (`v` 1: episodes, `SideSnoozes`, Mute all) and `AlertEngine.restore(_:context:at:)`;
  `Snapshot.decode` returns nil for unreadable / other-version data (start fresh). The settings are not in it: they
  are the SPEC §3 JSON (`AlertSettings.encoded()` / `.decode`, lenient, coerced to the generated `SettingsSpec`  number formats) stored under the schema key `alerts`, and on a follower they may come from the wearer.
- `SideSnoozes` — the per-side Mute / Remind less with the one margin rule (`advance`), shared by the engine and
  `AlertMirror` (a follower mirroring the wearer's `alert` events: which show here, and this device's own actions).
- `AlertUIState` (`engine.uiState(at:)`) — active alert per group, quiet / relaxed sides with their exits, Mute all;
  `QuietReason` is typed, the words come from `MuteCopy` (`contracts/alerts/copy.json`, en) with `SukraraCore.Durations`.
- `AlertNotificationIDs` — category / action / notification ids (`app.mauri.sukrara.alert…`, `app.mauri.sukrara.muted`).
- Tests (`Tests/AlertsTests`, Swift Testing): `ScenarioTests` (one case per timeline in `contracts/alerts/scenarios`,
  the SPEC §7 driver; also run with an `AlertMirror` for `mirror` scenarios), `SettingsCodecTests`,
  `EpisodesRestoreTests`, `EngineTests` (safety invariants, effect order, clock jumps, deterministic ids),
  `MuteCopyTests`.

## Android sensor (`apps/android/sensor`, chunk 12)

The wearer's glucose input. `SensorModule` builds it; the hub reads `SourceManager` as its one `ReadingSource`
(`:core:model`).

- **Threading**: one serial dispatcher (`Dispatchers.Default.limitedParallelism(1)`) runs the source switch, the
  Libre 3 driver, the GATT queue and every timer; Binder callbacks are queued into an unlimited channel and handled
  there. Juggluco's blocking HTTP runs on IO. A switch, a restart (new pairing, *Try again*, Bluetooth on, watchdog)
  cancels the running source, whose `finally` closes the gatt, before the next one starts.
- **Layers**: `libre3/Libre3Driver` (protocol only, no Android; ported byte for byte from the PoC) → `ble/BleLink` →
  `ble/GattLink` (queue, 10 s per-operation and discovery timeouts, close-now + new connection with 1–30 s backoff,
  Bluetooth off/on, generation-tagged callbacks) → `ble/GattConnector` (`AndroidGatt` on the phone, fakes in tests).
  `libre3/Libre3Crypto` is the JNI core (`libsukrara_libre3.so`, from Juggluco at the pinned revision); a build
  without it shows *Libre 3 core missing* and release builds refuse to build (`verifyNativeCore`).
- **Storage**: the whole pairing (address, start, PIN, authorization, ended) is one SecureStore entry `sensor`
  (Keystore alias `sukrara.sensor`): one atomic write; unreadable → `PairingLoad.Corrupt` → *scan the sensor again*.
  Receiver IDs (V1's own, the LibreView account's + email) in the typed DataStore `receiver-ids.json`. No password or
  token is stored.
- **Takeover and receiver IDs** (verified from code and docs/02; device test pending): a Libre 3 is bound for life to
  the receiver ID it was started with; NFC takeover (`A8`) succeeds only with that ID and a wrong one changes nothing
  (`B1`). V1 starts sensors only with the LibreView account's ID (nothing sent without it) and tries account → own ID
  for a takeover (nothing sent without the account ID). A showcase-started sensor (showcase ≥ 2026-10-18 starts with
  the same account ID) is taken over by V1 signed in to the same account, and can be taken back by the showcase. A
  takeover gives a new BLE PIN, so V1 pairs afresh and the previous owner's Bluetooth session fails; if the sensor is
  taken away from V1, 3 rejected authorizations in a row stop the link with *another app has the sensor*.
- **Juggluco**: `juggluco/JugglucoSource` reads `http://127.0.0.1:17580` (loopback, allow-listed): history, then SSE.
