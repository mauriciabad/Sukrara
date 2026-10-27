# apps/android — Sukrara V1 for Android

Sukrara V1's Android app: Kotlin only (JNI/C is fine), minSdk 36 / targetSdk 37, Jetpack Compose, GPL header on
every file. A clean rewrite of the PoC in [`poc/spikes/showcase-android`](../../poc/spikes/showcase-android); it reads
the shared specs and fixtures from [`contracts/`](../../contracts/) and builds the Libre 3 native core from a local
Juggluco checkout (a release build fails without it). Architecture: [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
§Android. Setup (JDK 21, SDK, emulator, signing): [`docs/DEVELOPMENT.md`](../../docs/DEVELOPMENT.md).

## Build and test

```sh
cd apps/android
./gradlew :core:alerts:test             # one JVM module's tests (fast: prefer targeted tasks)
./gradlew :monitor:testDebugUnitTest    # one Android module's tests
./gradlew :app:assembleDebug            # debug APK (app.mauri.sukrara, debug key)
./gradlew check                         # everything CI runs: unit tests + lint (slow)
```

Several agents build in parallel on one 16 GB Mac: run the tasks of the module you changed, not full builds;
`gradle.properties` keeps each build small (2 GB heap, 3 workers).

## Structure

| module | type | owns |
|---|---|---|
| `:app` | app | `App`, `AppGraph` (DI), `MainActivity` + Navigation 3, the merged manifest, `src/debug` tools |
| `:core:model` | JVM | shared types and ports: `Reading`, `Treatment`, `Clock`, `GlucoseState` + `GlucoseHub`, storage interfaces (**additive only**) |
| `:core:testing` | JVM | fakes (`FakeClock`, `InMemory*`, `TestDispatchers`) and `Contracts` — `testImplementation` only |
| `:core:alerts` | JVM | the pure alert engine |
| `:core:protocol` | JVM | Nostr protocol v2 wire formats (no nostr-sdk) |
| `:core:data` | Android | DataStore settings/state, SecureStore, Room |
| `:core:designsystem` | Android | theme, motion, shared rows |
| `:core:graph` | Android | the one card/graph renderer |
| `:monitor` | Android | `GlucoseHub` implementation, monitoring service, alert runtime, notifications |
| `:sensor` | Android + JNI | Bluetooth link, Libre 3 (native core `Libre3Crypto`), pairing, Juggluco source |
| `:share` | Android | relays (nostr-sdk), owner/follower engines, nearby (BLE) share |
| `:integrations` | Android | uploads, Health Connect, broadcasts, local servers, CSV export |
| `:pen` | Android | insulin pens (NFC) |
| `:widgets` | Android | Glance widgets |
| `:feature:*` | Android | screens: `home`, `settings`, `sharing` (incl. first-run setup), `sensor`, `treatments` |

Packages follow the module path: `:feature:home` → `app.mauri.sukrara.feature.home`. The module list is fixed in
`settings.gradle.kts`; new code goes into an existing module.

## How to add code

- **A module's build file** only applies convention plugins and lists dependencies:
  `id("sukrara.android.library")` (or `sukrara.jvm.library`), plus `sukrara.android.compose` for UI,
  `sukrara.room` for a Room database, `sukrara.contracts` when tests read `contracts/` (use `Contracts.file("…")`).
  Versions come only from `gradle/libs.versions.toml`.
- **Dependencies between modules point down**: features → core/runtime modules → `:core:model`. Features never
  depend on each other or on `:app`; they declare what they need as an `XxxDeps` interface (see `HomeDeps`).
- **DI**: your area gets ONE module class in your module (e.g. `class MonitorModule(…)`) that builds its objects
  from constructor parameters. `AppGraph` composes them, one `by lazy` block each, in dependency order
  (`data` → `role` → `sensor` → `monitor` → `alerts` → …); a new area adds:
  1. one `val share by lazy { ShareModule(…) }` in `AppGraph`, its inputs taken from the modules above it
     (`data.settings`, `data.typedStore("<name>", …, whenCorrupt = …)`, `monitor.hub`, `clock`, `dispatchers`);
  2. if it has a load function, one call in `AppGraph.ready` (a lazy `Deferred` on the IO dispatcher, started by
     `App`; `await()` it before reading state). No I/O in constructors;
  3. if Android components (service, receivers, provider) need it, an `XxxHost` interface in its module that `App`
     implements with one line (`override val alerts get() = graph.alerts`, like `MonitorHost` / `AlertsHost`);
  4. feature screens get it through their `XxxDeps` interface, implemented by `AppGraph` delegating to the module.
  ViewModels are created with `viewModelFactory { initializer { … } }` from the deps.
- **Who starts what**: `App.onCreate` builds the graph and starts `ready`; `MainActivity.onResume` calls
  `monitor.starter.start(StartReason.App)`; the service attaches the role's `ReadingSource` (wearer: the sensor's
  `SourceManager`, run only while attached — `RunWhileAttached`; follower: chunk 25) and the alert runtime.
- **Debug fakes**: `app/src/debug` has `DebugReceiver` / `DebugFakes` and `BuildVariant` (the release copy in
  `app/src/release` returns no test source). `adb shell am broadcast -n app.mauri.sukrara/.debug.DebugReceiver
  -a app.mauri.sukrara.debug.SYNTHETIC --ez on true [--ei cycle 135] [--ez frozen true]` replaces the sensor with a
  made-up 45–215 mg/dL curve on the emulator (`cycle 135` = its lowest point now); `--ez on false` removes it.
- **Manifest**: a module declares its own components in its `src/main/AndroidManifest.xml`. Exported components
  need a permission (ManifestTest lists the allowed exceptions). Debug-only tools live in `app/src/debug`.
- **Strings**: every user-visible text in the module's `res/values/strings.xml` and `res/values-es/strings.xml`.
- **Time and threads**: inject `Clock` and `DispatcherProvider` (from `:core:model`); tests use `FakeClock` and
  `TestDispatchers`.
- **Tests**: JUnit 4 + kotlinx-coroutines-test in `src/test/kotlin`; Robolectric only when Android is really needed.
- **Formatting**: `./gradlew spotlessApply` (ktlint + Compose rules + the GPL header); `check` fails on drift.

## Shared interfaces (`:core:model`, additive only)

```kotlin
data class Reading(val t: Instant, val mgdl: Int, val rateMgdlPerMin: Double?, val source: SourceId)
@JvmInline value class SourceId(val id: String)          // Libre3, Juggluco, Relay, Nearby
enum class Trend; enum class Role { Wearer, Follower }
data class Treatment(id, kind: TreatmentKind, amount, at, note, modifiedAt, deleted, origin: TreatmentOrigin)
interface Clock { fun now(): Instant; val zone: ZoneId }  // SystemClock reads the zone on every access
interface DispatcherProvider { val main; val default; val io }   // DefaultDispatchers
data class GlucoseState(role, latest, recent, sensor: SensorState, source: SourceStatus, alerts: AlertUiState, link: LinkState?)
interface GlucoseHub { val state: StateFlow<GlucoseState>; suspend fun ingest(readings, from: SourceId); fun setSource(status) }
// core.model.storage — implemented by :core:data, faked by :core:testing
interface SettingSpec<T> { val key: String; val default: T }
interface SettingsRepository { fun <T> flow(spec): Flow<T>; suspend fun <T> set(spec, value) }
interface StateStore<T> { val data: Flow<T>; suspend fun update(transform: (T) -> T) }
interface SecureStore { suspend fun read(name): ByteArray?; suspend fun write(name, bytes); suspend fun delete(name) }
interface ReadingsRepository { fun window(from: Instant): Flow<List<Reading>>; suspend fun upsert(readings) }
```

`GlucoseState.Initial` = nothing known, `SourceStatus.NotStarted` (no missing-data alarm before monitoring starts).
Fakes in `:core:testing`: `FakeClock` (settable time and zone), `TestDispatchers`, `InMemorySettingsRepository`,
`InMemoryStateStore`, `InMemorySecureStore`, `InMemoryReadingsRepository`, `Contracts.file(path)`.

## Android components (merged manifest)

| component | module | state |
|---|---|---|
| `MainActivity` (launcher, `sukrara:`, App Link `https://sukrara.mauri.app/join*`), `HealthPermissionsRationale` alias | `:app` | live |
| `debug.DebugReceiver` (exported, `DUMP`) | `:app` `src/debug` | debug builds only |
| `MonitoringService` (FGS `connectedDevice`), `BootReceiver`, `AlertCheckReceiver`, `AlertActionReceiver`, `HealthCheckReceiver` | `:monitor` | skeleton, enabled (10/11 fill) |
| `AlertSoundProvider` `${applicationId}.alertsound` (exported read-only for System UI) | `:monitor` | skeleton (11) |
| `SensorTapActivity` (NFC, `DISPATCH_NFC_MESSAGE`) | `:sensor` | disabled until 12/18 |
| `PenTapActivity` (NFC), `PenReadActivity` | `:pen` | disabled until 19 |
| `TreatmentTileService`, `QuickTreatmentActivity` | `:feature:treatments` | disabled until 19 |
| `FileProvider` `${applicationId}.export` | `:integrations` | live (not exported) |
| Glance widget receivers + config activity | `:widgets` | added by chunk 20 |

A Bluetooth on/off receiver can't be declared in the manifest (`ACTION_STATE_CHANGED` isn't an exempt implicit
broadcast): `:sensor` / `:share` register it at runtime while monitoring runs.

## Release builds

- Version from `<repo>/VERSION` (`0.90.0` → versionCode 90000; `BuildConfig.PRERELEASE` while major is 0).
- Signed with `SUKRARA_KEYSTORE` (default `~/.android/sukrara-release.jks`, alias `sukrara`); the password comes only
  from env `SUKRARA_KEYSTORE_PASSWORD` (Gradle never reads the Keychain):
  `SUKRARA_KEYSTORE_PASSWORD="$(security find-generic-password -s sukrara-android-release -a sukrara -w)" ./gradlew :app:assembleRelease`.
- The Libre 3 core comes from Juggluco (`-Psukrara.juggluco=…`, env `SUKRARA_JUGGLUCO`, else
  `<main checkout>/third_party/Juggluco`) at the commit in `sensor/juggluco.lock`, clean tree. Debug builds without
  it only warn (the app reports the missing core). Any release output (`preReleaseBuild`) fails without the key or
  the pinned checkout, and `assembleRelease` / `installRelease` fail if the APK lacks `lib/arm64-v8a/libsukrara_libre3.so`.
- `local.properties` (`sdk.dir=…`) is needed once per clone/worktree when `ANDROID_HOME` isn't set.
