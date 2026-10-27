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
public struct SignedEvent; public struct RelayFilter { func matches(_:) -> Bool }      // see "Mac share" below
public protocol RelayTransport: Sendable { func subscribe(_: String, _: RelayFilter) async; func close(_: String) async
    func publish(_: SignedEvent) async; var inbound: AsyncStream<RelayInbound> { get } }  // RelayPool; FakeRelay in tests
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

## Mac share (`apps/macos/Sources/SukraraShare`)

```
FollowerSession (pure struct)  ──receive(event, now)──► Outcome { verdict, deltas, publish, resubscribe }
   │ uses                                     ▲
   ▼                                          │ events (relays or BLE, same rules)
NostrCrypto (protocol) ◄── NostrSDKCrypto     RelayTransport (protocol) ◄── RelayPool (actor) ── WebSocketFactory
   (only file importing NostrSDK 0.45.1)                                 ◄── FakeRelay (SukraraTesting)
```

- **Protocol/** — `Wire` (kinds, TTLs, tag derivations, match code, `Wire.defaultRelays` = the only `wss://` hosts),
  `Derive` (HKDF invite key, CryptoKit), `SignedEvent` (NIP-01 JSON; engines never see NostrSDK types),
  `SecretKeyHex` (redacted in `description`, `debugDescription` and `Mirror`), typed payload decoders with the
  reference's strict rules (`Payloads.swift`), `Profile`/`Avatar` (wire + v1 normalisation; drawing is UI),
  `InviteLink` + `InviteOpener` (v3 short link, fingerprint / expiry checks), `FollowerMessages` (request, profile,
  rekey, history gift wraps with absolute expiration; writer treatment events).
- **Follower/** — `FollowerSession`: port of `poc/spec/reference/ogs/follower.py` plus the settings rule; verdict
  strings are the OGS vector codes shared with Android's `StreamFollower`. `FollowerState` (`v` 1) holds F, the read
  keys, roster, writer-scope records (creator, held `(u, o)` per treatment id), settings `u`, sensor/roster ordering
  and recent alert keys: everything trust depends on, so a restart can't be used to slip a forged entry in. It is
  stored by chunk 15 in the Keychain (service `app.mauri.sukrara`, account `follower`). Readings/treatments go out as
  deltas to 15's stores. Unfollow = discard the state.
- **Relay/** — `RelayPool` actor: socket per `wss://` relay, jittered backoff 2 s→5 min, subscriptions (deduped by
  filter) and a 1 h outbox re-sent on reconnect, NIP-42 AUTH as the pool's key (F, or the invite key for an invite
  lookup pool), EOSE/OK/CLOSED/connection status on a single-consumer `inbound` stream, ping at 150 s silence,
  reconnect at 270 s. It does not dedupe events (the session does; an unknown-epoch event must be re-processable).
- **Tests** (`ShareTests`): OGS vectors (derivations, every event verifies/opens, 27-step acceptance scenario,
  invite checks, treatment payloads), identity vectors, profile fixture, `contracts/protocol/sync/*.json` (made by the
  Android tests: Mac passes them unchanged → both followers agree), fake-relay end-to-end, RelayPool over scripted
  sockets. Nothing opens a network connection.

## Mac runtime (follower, stores, nearby, widget feed)

```
AppModel (21, @MainActor) ── snapshots()/alerts() ──► FollowerService (protocol) ◄── FollowerRuntime (actor)
                                                        │ owns
          FollowerSession ◄─ receive ─┬─ RelayLink (RelayPool | FakeRelay)   KeychainStore (SecretStoring)
                                      └─ NearbyTransport (NearbyCentral | fake) ─ NearbyLink (pure BLE protocol)
          stores: ReadingsFileStore · TreatmentFileStore · SensorStateStore · WearerSettingsStore (actors)
WidgetFeed (SukraraApp) ── glucose.json in the App Group + WidgetReloadPolicy (≤ 1 value reload / 5 min)
```

- **Stores** (`SukraraCore/Stores`): `readings.csv` (`t_ms,mgdl,rate`, appended, atomic rewrite for backfill,
  31 days, first reading per `t` kept), `treatments.json` (`TreatmentLog` v1: entry + creator + delivery),
  `sensor.json`, `wearer-settings.json`. An unreadable file is copied to `<name>.corrupt-<unix s>` and reported as a
  `StoreIssue` (never silent "no data"). `KeychainStore` uses the login keychain: the Apple Development signature has
  no provisioning profile, so the data-protection keychain answers -34018 (TN3137); that error is tested to surface.
- **FollowerRuntime**: start reads the Keychain (missing item → wipe leftovers, not following; read error → keep
  data, `broken`), connects relays (+ Bluetooth when allowed), resubscribes when the session asks, persists trust
  changes at once and the rest ≤ every 5 min, re-sends unsent treatments, runs the missing-history check 90 s after
  the backfill. `reconnect()` is for wake / network-up (21 wires `NSWorkspace.didWakeNotification` and
  NWPathMonitor). Join = invite lookup pool (invite key, `#p`) → `FollowerSession.join` → Keychain → request.
- **Nearby** (`SukraraShare/Nearby`): `BleFraming` / `BleReassembler` / `BleMessage` / `HelloProof` pinned by the
  OGS `ble` vectors; `NearbyLink` is the per-connection protocol (HELLO, INFO proof, one write in flight, nothing
  written before the phone proved it's the wearer's); `NearbyCentral` is the CoreBluetooth adapter, an actor whose
  executor is the CB queue (delegate callbacks enter with `assumeIsolated`).
- Tests: `FollowerRuntimeTests` (join over a fake relay hub with the OGS invite and key, outbox, unfollow, Keychain
  errors), `ProtocolCompatTests` (every sync fixture through the runtime into file stores, incl. `rotation.json` =
  restore), `NearbyTests`, `StoreTests`, `KeychainStoreTests` (temp service), `WidgetFeedTests`.

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
