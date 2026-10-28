# apps/macos — Sukrara for Mac

A menu bar app (`Sukrara.app`, `LSUIElement`) with a WidgetKit extension (`SukraraWidget.appex`), tested against [`contracts/`](../../contracts/).
Architecture and the interfaces other parts implement: [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) (Mac).

## Build, test, bundle

```sh
cd apps/macos
swift build                      # debug build of every target (Swift 6 language mode)
swift test                       # Swift Testing; fixtures from contracts/ (SUKRARA_CONTRACTS overrides the path)
swift format lint --strict -r Package.swift Sources Tests
Bundle/bundle.sh                 # build/Sukrara.app + PlugIns/SukraraWidget.appex, signed (Apple Development, team QWUV9H4A3G)
Bundle/bundle.sh --unsigned      # ad hoc signed, for CI / machines without the identity
Bundle/bundle.sh --dev           # CFBundleVersion <code>.<yyMMddHHmm> so the widget host refreshes previews
Bundle/dmg.sh                    # build/Sukrara-<version>.dmg + build/Sukrara-mac-<version>.zip
```

Install only with `tools/install.sh mac` (never copy `build/Sukrara.app` into `~/Applications` by hand).

## Targets

| Target | What | Notes |
|---|---|---|
| `SukraraCore` | ids, paths, models (reading, unit, trend, treatment + merge), durations, CSV export, store protocols, settings spec | nonisolated, extension-safe |
| `SukraraGraph` | the one card renderer: `GlucoseCard.image` / `draw` / `face` / `accessibilityLabel` over `CardData`, `CardSurface.style`, `WidgetSnapshot` | extension-safe; tested over `contracts/graph` |
| `SukraraAlerts` | the alert engine (pure, `contracts/alerts`) | |
| `SukraraShare` | protocol v2, relays, nearby (NostrSDK 0.45.1) | every transport behind a protocol |
| `SukraraApp` | AppDelegate, `AppModel`, windows, `LaunchPlan` | MainActor by default |
| `Sukrara` | the app executable (`main.swift` → `AppMain.run()`) | |
| `SukraraWidget` | the widget extension executable | linked with `_NSExtensionMain` |
| `sukrara-snap` | offline snapshot renderer (debug builds only) | the only snapshot path |
| `SukraraTesting` | fakes for every protocol + `Contracts.url(_:)` | tests only |

## Rules that are specific to the Mac app

- **Every launch goes through `LaunchPlan`** (`Sources/SukraraApp/Launch/LaunchMode.swift`): unknown arguments exit 2,
  a dev build (debug, bare executable, or a bundle inside a git checkout) exits 3 without `SUKRARA_DEV=1`, and with it
  runs offline on separate data (`DataScope.isolated`: Keychain service and defaults suite `app.mauri.sukrara.dev`).
  Services start only through `ServicesPlan.start(using:)`, limited by the user's settings with `limited(by:)`.
- **Tests never reach real relays, Bluetooth or the user's data**: use the fakes in `Tests/SukraraTesting`.
- **No debug entry points in release**: debug-only code is `#if DEBUG` or lives in `sukrara-snap` / tests.
- The widget reads only the App Group snapshot (`SukraraPaths.appGroupSnapshot()`), never a home-based path.
