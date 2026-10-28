# Developing Sukrara

The know-how for whoever works on Sukrara (the apps in `apps/`, shared `contracts/`, `tools/`). The
rules are in [`CLAUDE.md`](../CLAUDE.md); the architecture in [`ARCHITECTURE.md`](ARCHITECTURE.md); the frozen PoC's
own notes stay in [`poc/spikes/showcase-android/NEXT.md`](../poc/spikes/showcase-android/NEXT.md).

## Setup

- Clone, then `tools/hooks/install.sh` once (sets `core.hooksPath=tools/hooks`, relative, so worktrees share it).
- **JDK 21** for everything Android: `brew install openjdk@21`; Gradle's toolchain finds it (`JAVA_HOME` =
  `$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home` for keytool & co.). The build fails without it.
- Android SDK at `~/Library/Android/sdk`; emulator binary `~/Library/Android/sdk/emulator/emulator` (at most two
  emulators at once); the `android` CLI at `~/.local/bin/android` (create / start AVDs, screenshots, UI dumps).
- Xcode (Swift 6) for the Mac app; `swift build` / `swift test` in `apps/macos`.
- The Libre 3 native core builds from `third_party/Juggluco` (gitignored, pinned by `apps/android/sensor/juggluco.lock`;
  from a worktree the main checkout's copy is found). A release build fails without it.

## Everyday commands

| What | Command |
|---|---|
| All repo rules (CLAUDE.md as checks) | `tools/check/run.sh` (`--staged`, `--range A..B`, `--pending`, `--help`) |
| The checks' own tests | `tools/check/run.sh --selftest` |
| Cross-surface card tests | `tools/check/run.sh --layout` |
| Settings code after a schema change | `python3 tools/settings/gen.py` (commit both SettingsSpec files) |
| Android: everything CI runs | `cd apps/android && ./gradlew check` |
| Mac: build + tests | `cd apps/macos && swift build && swift test` |
| Version | `tools/release/version.sh` → `0.12.0 12000 prerelease` (from `VERSION`) |

CI (`.github/workflows/checks.yml`) runs the rules on every push and the Android / Mac jobs when their paths (or
`contracts/`, `tools/`, `VERSION`, `assets/`) change; `gate` is the only required check.

## Devices

- **Phone** Pixel 10 Pro `62240DLCH0005W`. The owner relies on it daily: only in-place updates (`adb -s … install -r`
  keeps data), read-only adb otherwise. **Never** take the sensor from the running app without the owner's OK, never
  post to LibreView (real doctor-facing account), never uninstall / clear data (identity + followers), no reboots, no
  global Wi-Fi / Bluetooth toggles. Always `adb -s <serial>`.
- **Mac**: the owner's copy is `~/Applications/Sukrara.app` (team-signed, with the widget). Never overwrite it with a
  test build; test builds run from another path, offline (`LaunchMode`: snapshot / test runs start no Bluetooth,
  relays or alerts). After replacing the app, `pkill -f SukraraWidget.appex` and keep `pluginkit -mAv | grep sukrara`
  pointing only at `~/Applications/Sukrara.app`.

## Install (one build line)

Every install must contain all merged work, or features silently vanish from the owner's devices:
`tools/install.sh phone|mac [--dry-run]`. It refuses a dirty tree, a HEAD without `origin/main`, the branches in
`tools/check/integration.txt` or the commit installed last time, and failing checks; then it builds the release,
verifies it (`tools/release/verify-apk.sh` / `verify-app.sh`), installs in place and records the commit in
`<git common dir>/sukrara-installed-<target>`. On the Mac it also makes the installed app the `sukrara:` link handler.

## Signing

- **Android release key**: `~/.android/sukrara-release.jks` (alias `sukrara`), made once by
  `tools/release/make-keystore.sh`; its password lives in the Keychain item `sukrara-android-release`. The release
  build reads `SUKRARA_KEYSTORE` (default that path) and the env `SUKRARA_KEYSTORE_PASSWORD` only; `tools/install.sh` and
  the release scripts export it from the Keychain item.
  Its public SHA-256 is `tools/release/android-cert.sha256`. Backup procedure: [docs/20](20-publishing.md#the-android-release-key).
  Debug builds use `~/.android/debug.keystore` (`tools/release/android-debug-cert.sha256`, also in assetlinks).
- **Mac**: the Apple Development identity of team `QWUV9H4A3G` (no notarization yet); App Group
  `QWUV9H4A3G.app.mauri.sukrara`.

## Strings (Android, English + Spain Spanish)

Every user-visible text goes in the owning module's `src/main/res/values/strings.xml` and the same key in
`values-es/strings.xml` (snake_case keys grouped by screen, a `<!-- what it is [CHAR LIMIT=…] -->` comment, `%1$s`
positional arguments, `<plurals>` for counts, `translatable="false"` for brand names and units; Spanish terms:
hidratos, insulina rápida / lenta, tiempo en rango). Compose reads `stringResource`; `StringsParityTest` and the
`strings` check fail on a missing key.

## Debug and test code

Debug commands that replace the sensor or make up data live only in `src/debug` (Android) or `#if DEBUG` / the
`sukrara-snap` and test targets (Mac): a release build cannot contain them (`debug` check, `verify-apk.sh`,
`verify-app.sh`). Test with synthetic data on the emulator, never the owner's devices or real relays.

## Orchestration that worked

One worktree per agent from the integration branch (`next`), a shared interface committed before dispatch,
protocol notes committed early, scaffolds (`NNa`) merged before the wave that builds on them, phone ownership handed
over explicitly between sessions.
