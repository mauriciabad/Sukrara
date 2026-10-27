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

### Sensor
<!-- Chunks 12 / 18: GattLink, Libre 3, pairing store, new-sensor flow. -->

### Sharing
<!-- Chunks 06 / 13 / 24 / 25: protocol, owner / follower engines, nearby share, first-run setup. -->

### Card, widgets and UI
<!-- Chunks 08 / 16 / 17 / 20: the one renderer, home, settings, widgets. -->

### Integrations and pens
<!-- Chunks 19 / 26. -->

## Mac (`apps/macos`)

### Targets
<!-- Chunk 03: SukraraCore, SukraraGraph, SukraraAlerts, SukraraShare, SukraraApp, Sukrara, SukraraWidget, sukrara-snap. -->

### Launch modes and concurrency
<!-- Chunk 03: LaunchMode (normal | snapshot | test → services plan), actors, MainActor UI. -->

### Follower runtime and alerts
<!-- Chunks 05 / 07 / 15 / 21. -->

### Menu bar, windows and widget
<!-- Chunks 14 / 21 / 22 / 23. -->

## Contracts and tests

Both apps' tests read `contracts/` (Android: the `sukrara.contracts` system property from the convention plugin;
Mac: `Contracts.url(…)`). A contract change lands with both implementations (`tools/check` parity groups, CI runs
both apps). Alert scenario expectations change only with the owner's OK.

## Release

`VERSION` → both apps. Android: `tools/release/android-release.sh` (assembleRelease with the release key +
`verify-apk.sh`); Mac: `tools/release/mac-release.sh` (bundle + dmg + `verify-app.sh`). 0.x versions are GitHub
prereleases; both apps' update checks include prereleases only on a prerelease build.
