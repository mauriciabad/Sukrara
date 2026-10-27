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
  from constructor parameters. Add one `val monitor by lazy { MonitorModule(…) }` line to `AppGraph` and implement
  the feature `XxxDeps` members by delegating to it. No I/O in constructors: disk work goes in a `suspend fun
  ready()` that `AppGraph.ready()` calls on the IO dispatcher. ViewModels are created with
  `viewModelFactory { initializer { … } }` from the deps.
- **Manifest**: a module declares its own components in its `src/main/AndroidManifest.xml`. Exported components
  need a permission (ManifestTest lists the allowed exceptions). Debug-only tools live in `app/src/debug`.
- **Strings**: every user-visible text in the module's `res/values/strings.xml` and `res/values-es/strings.xml`.
- **Time and threads**: inject `Clock` and `DispatcherProvider` (from `:core:model`); tests use `FakeClock` and
  `TestDispatchers`.
- **Tests**: JUnit 4 + kotlinx-coroutines-test in `src/test/kotlin`; Robolectric only when Android is really needed.
