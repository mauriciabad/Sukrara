# Sukrara V1 architecture

How Sukrara V1 is built. Each part is filled in by the work that builds it; the rules are in
[`CLAUDE.md`](../CLAUDE.md), the day-to-day know-how in [`DEVELOPMENT.md`](DEVELOPMENT.md), what the apps do in
[`FEATURES.md`](FEATURES.md).

## Repository layout

| Path | What |
|---|---|
| `apps/android/` | the Android app (Gradle root: own `settings.gradle.kts`, wrapper, `build-logic/`) |
| `apps/macos/` | the Mac app (SwiftPM root: `Package.swift`, `Sources/`, `Tests/`, `Bundle/`) |
| `contracts/` | what both apps agree on and are tested against: alert spec + scenarios, graph fixtures + surface registry, settings schema, protocols + vectors, treatment / export / pen fixtures ([README](../contracts/README.md)) |
| `assets/brand/` | icons and brand art for both apps and the docs |
| `poc/` | the frozen showcase PoC (0.11.x) and its investigations; buildable, never changed |
| `tools/` | `check/` (the rules as checks), `hooks/`, `settings/gen.py`, `release/`, `install.sh` |
| `VERSION` | the one version source (`0.90.0` → versionCode / CFBundleVersion 90000) |

## Identity and storage

- Android `app.mauri.sukrara`; Mac `app.mauri.sukrara`, widget `app.mauri.sukrara.widget`, App Group
  `QWUV9H4A3G.app.mauri.sukrara`. V1 installs next to the PoC (`app.mauri.sukrara.poc`) and reads none of its data.
- Settings: [`contracts/settings/settings.json`](../contracts/settings/settings.json) → generated `SettingsSpec`
  (Android Preferences DataStore files by area, Mac UserDefaults). Every persisted format carries a version.

## Android (`apps/android`)

### Build
<!-- Chunk 02: Gradle / AGP / Kotlin versions, convention plugins, signing, native core guard. -->

### Modules
<!-- Chunk 02: the module graph and what each module owns. -->

### State and dependency injection
<!-- Chunks 02 / 10: AppGraph, GlucoseHub, dispatchers, error policy. -->

### Services, alerts and notifications
<!-- Chunks 10 / 11: MonitoringService, alert runtime, channel registry. -->

**Alerts (chunk 11, `:monitor` `monitor/alerts/`).** The rules are `:core:alerts` (`AlertMachine`); the runtime only
applies them.
- `AlertRuntime` — the one owner of the machine per process, behind one `Mutex`. Inputs: hub state (role, latest
  reading, `monitoringStartedAt`, sensor), alert settings (store `alerts`, key `alerts`, SPEC §3 JSON via
  `AlertStateStore`), treatments (→ `Doses`), the wearer's mirrored settings and `alert` events (25), this device's
  actions (`AlertControls` in `:core:model`, also the notification buttons). Per input: machine → effects applied
  (`AlertPresenter`, `AlertEventSink`, `AlertAlarm`) → `AlertRecord` saved (typed store) → `AlertUiState` published to
  the hub. Role `null` is ignored (setup / hub loading); `monitoringStartedAt == null` switches `no_data` off.
- `AlertCheckReceiver` (exact alarm, boot, update, clock / zone change) → `runtime.tick()` from the saved record with
  the service dead → `sensorEnd.check()` → `MonitorRestarter.restart()` (10's starter). `AlertActionReceiver` (Mute… /
  Change time direct reply, Remind less / as usual, Unmute, swipe tidy). Both non-exported, `goAsync` with a 9 s budget.
- `AlertNotifier` + `AlertChannels` (`alert.<kind>` / `alert_alarm.<kind>`) + `NotificationGroups` (`notify/`: the
  `alerts` and `info` notification groups with summaries; every alerting notification of the app belongs to one) +
  `AlertSoundProvider` (`${applicationId}.alertsound`, exported read-only for System UI) + `AlertHaptics`.
- `SensorEndNotifier` (`monitor/sensorend/`): `SensorEndPlan` (pure) + its own exact alarm.
- `AlertsModule` (A4) wires it; the Application implements `AlertsHost` so receivers and the provider reach it; the
  monitoring service calls `attach(scope)`.

### Sensor
<!-- Chunks 12 / 18: GattLink, Libre 3, pairing store, new-sensor flow. -->

### Sharing
<!-- Chunks 06 / 13 / 24 / 25: protocol, owner / follower engines, nearby share, first-run setup. -->

### Card, widgets and UI
<!-- Chunks 08 / 16 / 17 / 20: the one renderer, home, settings, widgets. -->

### Integrations and pens
<!-- Chunks 19 / 26. -->

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

## Android storage (`apps/android/core/data`)

Ports live in `:core:model` (`storage/Storage.kt`, in-memory fakes in `:core:testing`); `:core:data` implements them
and `DataModule` (built lazily by `AppGraph`, `ready()` opens the database on IO) exposes them. No PoC files or keys are
read (new app id, no migration). Nothing is in cloud backup or device transfer (`allowBackup=false`,
`data_extraction_rules.xml` excludes every domain).

| What | Where | API |
|---|---|---|
| Settings | `datastore/<store>.preferences_pb`, one per schema store, keys from `contracts/settings/settings.json` | `SettingsRepository` + `SchemaSettings.number/bool/text(id)` |
| Typed state (alert episodes, sensor pairing, …) | `files/state/<name>.json` (DataStore + kotlinx JSON) | `DataModule.typedStore(name, serializer, initial, whenCorrupt)` |
| Secrets | `files/secure/<name>.bin` = `0x01` ‖ IV ‖ AES-256-GCM (AAD = name); Keystore alias `sukrara.<name>`, one key per purpose: `identity.owner`, `identity.follower`, `nightscout`, `tidepool`, `libreview`, `sensor` | `SecureStore` (`KeystoreSecureStore` on `SecretBox`) |
| History | `databases/sukrara.db` (Room v1, schema in `core/data/schemas/`): `readings` (PK t+source), `treatments` (LWW on `modified_at`, tombstones), `seen_wraps`, `rejects`, `outbox` | `ReadingsRepository`, `TreatmentsRepository`, DAOs for the sharing ledger |
| Other files | `files/<path>` atomic tmp + fsync + rename | `DataModule.fileStore(path, codec)` → `Loaded / Missing / Corrupt` |
| Diagnostics | `files/diag/diag.log` + `.1`, 512 KB each, `Redact` scrubs keys / tokens | `DiagLog` |
| Network | one OkHttp client (15 s connect, 30 s read, 60 s call, UA `Sukrara/<version>`, no body logging); one `SukraraJson` (`ignoreUnknownKeys`, `explicitNulls = false`) | `DataModule.http`, `DataModule.json` |

Retention (`Retention`, applied in `ready()`): readings 90 days, seen wraps / rejects / sent outbox 14 days.

**Corruption is never silent.** A file that can't be decoded is copied to `<file>.corrupt-<epochMs>` and reported to
`DataModule.corruption` (`CorruptionLog`), which `AppGraph.startupState` turns into a visible problem with *Copy
report*. `ready()` re-reports every kept copy at each start until the person acknowledges it (renamed `.seen-…`, still
kept), so a crash or a background start can't hide it. Typed stores continue from the owner's explicit `whenCorrupt`
value and expose their own `corruption` flow; a `FileStore` keeps the damaged file in place and returns `Corrupt` on
every load (never `Missing`) until a deliberate save or delete; settings read as defaults. A secret that can't be opened throws
`SecretUnreadableException` on every read and stays in place (an unreadable identity is never "no identity"); a
transient Keystore error (busy after an OS update) throws `transient = true`, is not reported and deletes nothing; keys
are never deleted by an error path. A damaged database is copied aside (with its WAL) and reported by
`QuarantiningOpenHelperFactory` before SQLite recreates it. The database is not encrypted yet (issue: SQLCipher).

## Android protocol (`apps/android/core/protocol`)

Nostr protocol v2 + identity ([`contracts/protocol`](../contracts/protocol/)) as one pure-JVM module: no Android, no
I/O, no clock (time is a parameter), no nostr-sdk. The wire formats are the PoC's byte for byte (kinds, tag order,
TTLs, JSON field names and number forms, invite link `https://sukrara.mauri.app/join#…`, BLE framing), so a restored
identity keeps working with followers on Sukrara 0.11 (Android and Mac).

- **Crypto seam**: `NostrCrypto` (sign / verify, NIP-44 v2, NIP-59 gift wraps, nsec / npub, NIP-49). `:share`
  implements it with `org.nostrdevkit:nostr-sdk` (AAR, chunk 13). The module's tests use the JVM build of the same
  library (`nostr-sdk-jvm`, test classpath only). `testFixtures` ships `NostrCryptoContractTest` (OGS vectors: every
  event verifies / decrypts, wraps open to their rumor and its `created_at`, a rumor whose author isn't the seal signer
  is refused, NIP-49 and nsec round trips, the BLE HELLO proof); every implementation subclasses it. The PoC's
  hand-written BIP-340 verifier is gone: the HELLO proof is checked by the library's `Event.verify()`. HKDF is RFC 5869
  over the JCA's HMAC, pinned by RFC 5869 case 1 and `identity/vectors.json`.
- **Keys**: `PublicKeyHex`, `SecretKey` (redacted `toString`, hex only via `revealHex()`), `KeyPair`, `Derive.inviteKey`.
- **Events** (`event/`): payload codecs per family (`ReadingsCodec` live / sensor / chunk, `AlertCodec`,
  `TreatmentCodec` + `WriterScope`, `SettingsCodec`, `RosterCodec`, `BackupCodec` — reads what the showcase writes,
  `RumorCodec` for 30500 / 30501 / 30502, `Profile` / `Avatar`) and `EventFactory`, which signs every event the
  wearer and writers publish. Decoders throw only `InvalidPayload`; health payloads redact `toString`.
- **Follower acceptance** (`follow/StreamFollower`): the transport-independent judge for relays and Bluetooth: verdict
  `Accepted(item) | Rejected(reason) | Ignored(reason, rekey?)` with the codes of the OGS / sync fixtures. It keeps only
  trust and dedupe state (`FollowerState`: status, sid, epochs, roster, writer-scope creators, settings `u`, seen ids
  pruned at their expiry, rekey throttle); storing readings / treatments / settings is the follower engine's (25).
  A port of the reference follower plus, from the PoC `FollowerEngine`: the settings rule, a key delivery newer than
  the revoked notice re-approves (older notices ignored), a newer epoch's key outranks older-epoch rosters, rekey only
  for events newer than the latest key with `have` = newest held `gk`. Events rejected only because the author isn't
  in the roster yet aren't remembered, so they're judged again after the roster arrives. Dropped from the PoC engine:
  the 24 h treatment window and the `via` tag (UI / engine concerns).
- **Owner side** (`owner/`): `IdentityConflict.check` (A3: a newer roster without this device, under an epoch we
  don't hold, or a newer backup with other epochs = another app publishing as this identity → `Conflict`; our own
  echoes, our own rotation and late old events are `Fine`; the engine persists `OwnerMarks`), `RecoveryCode` (NIP-49
  export log_n 18 / key_security 0x01; import of ncryptsec, nsec or hex for the first-run restore).
- **Invites** (`invite/`): `InviteLink` parse / build, `Invites.create` / `open` (fingerprint, expiry, typed failure),
  match code. **BLE** (`ble/`): UUIDs, framing, `Reassembler` and `RateLimit` (limits of B§4.3 / B§8), HELLO / INFO
  messages, `HelloProof`.
- **Fixtures**: `contracts/protocol/{identity,ogs}` vectors and `contracts/protocol/sync/*.json` (owner → follower
  sequences and the owner-conflict cases, generated here with `-Psukrara.regen=true`, replayed by the Mac too).

## Android sharing, wearer side (`apps/android/share`)

- `crypto/SdkNostrCrypto` — the `:core:protocol` `NostrCrypto` over `org.nostrdevkit:nostr-sdk` 0.45.1 (+ JNA AAR;
  R8 keeps in `consumer-rules.pro`). Host tests run it on `nostr-sdk-jvm` and subclass `NostrCryptoContractTest`.
- `transport/` — `RelayTransport` (inbound flow, per-relay status, subscribe / publish / fetch, `reconnectNow`) and
  `RelayPool`, the Kotlin twin of the Mac `RelayPool`: one OkHttp WebSocket per relay (`OkHttpWebSockets`, wss only,
  pings every 60 s), backoff 2 s → 5 min × jitter, subscriptions + a 1-hour outbox re-sent after reconnects (a newer
  live / addressable event replaces the older), NIP-42 AUTH as the identity. nostr-sdk's Client isn't used: one relay
  behaviour on both platforms, and the pool is unit-tested against fake sockets. Tests use `FakeRelayTransport`.
- `owner/` — `OwnerState` (V1 typed state, `@Serializable`, redacted) in `OwnerIdentityStore` (SecureStore
  `identity.owner`; unreadable ≠ missing). Use-cases in `owner/usecase` are pure `(state, input, now) → Step(state,
  events)`: `CreateIdentity`, `RestoreIdentity` (+ `restoreAllowed` gate), `ExportRecovery`, `CreateInvite`,
  `HandleInbox`, `ApproveRequest`, `DenyRequest`, `RemoveFollower`, `Rotate`, `PublishReadings`,
  `IdentityConflictDetector`; control events (deliveries, notices, roster, backup) wait in `pendingControl` until a
  relay answers OK, so a rotation interrupted by a crash still reaches every follower. `OwnerEngine` runs them on one
  serial dispatcher, persists before publishing and exposes `StateFlow<OwnerShareState>`; `ShareOwnerModule` (A4)
  builds it for `AppGraph`.

## Contracts and tests

Both apps' tests read `contracts/` (Android: the `sukrara.contracts` system property from the convention plugin;
Mac: `Contracts.url(…)`). A contract change lands with both implementations (`tools/check` parity groups, CI runs
both apps). Alert scenario expectations change only with the owner's OK.

## Release

`VERSION` → both apps. Android: `tools/release/android-release.sh` (assembleRelease with the release key +
`verify-apk.sh`); Mac: `tools/release/mac-release.sh` (bundle + dmg + `verify-app.sh`). 0.x versions are GitHub
prereleases; both apps' update checks include prereleases only on a prerelease build.
