# contracts — what every app agrees on

The cross-platform specs, schemas and fixtures that every Sukrara app (Android [`apps/android`](../apps/android/),
Mac [`apps/macos`](../apps/macos/), …) consumes and is tested against, so the apps never diverge.

**Change rule:** a change here lands with every implementation (and their tests) in the same change. `tools/check`
enforces it (parity groups `alert-scenarios`, `alert-spec`, `graph`, `settings`, `protocol-v2`, `identity`,
`treatments`, `export`; skip only with a `Parity-skip: <group>: <reason>` trailer) and CI runs every app's tests when
anything here changes. **Alert scenario expectations never change without the owner's OK.**

Formats are JSON / TXT / MD (+ the alert sound files). No file here names app source paths, except
`graph/surfaces/surfaces.json` (the surface registry, checked by `tools/check`). The frozen PoC keeps its own copies
under [`poc/`](../poc/); the apps never read those.

How the apps read these files: Android tests get the folder from the `sukrara.contracts` system property (convention
plugin `sukrara.contracts`, a Gradle input so caching sees changes); the Android alert sounds are a resource source
directory. Mac tests resolve `Contracts.url(…)` (env `SUKRARA_CONTRACTS`, else walking up from `#filePath`);
`apps/macos/Bundle/bundle.sh` copies the `.caf` sounds.

| Path | What | Read by |
|---|---|---|
| `alerts/SPEC.md` | the alert rules: kinds, thresholds, repeats, mutes, episodes, the alert settings JSON (§3) | `:core:alerts`, `SukraraAlerts` |
| `alerts/scenarios/*.txt` | timeline scenarios: event sequences → expected alerts | `AlertScenarioTest` (Android), `AlertsTests` (Mac) |
| `alerts/copy.json` | alert texts (titles, mute pill, actions) every platform must show | every alert notifier + tests |
| `alerts/sounds/mac/*.caf`, `alerts/sounds/android/raw/*.ogg` | the four sound packs × eight kinds × three intensities (+ silence) | Mac bundle, Android `:monitor` resources |
| `graph/**` | the card / graph fixtures (layout scenes + rules + expectations, freshness, labels, sensor, clock, share, mark, place, status) | `:core:graph`, `SukraraGraph` tests |
| `graph/surfaces/surfaces.json` | THE registry of every place that shows the card or its value (app paths) | `tools/check` card rules, cross-surface tests |
| `settings/settings.json` | THE settings schema: every setting, its store + key per platform, number formats, mirroring | `tools/settings/gen.py` → `SettingsSpec`, `SettingsSchemaTest` on every app, `tools/check` |
| `settings/durations.json` | duration formatting cases ("1 h 30 min") | every `Durations` test |
| `settings/README.md` | the settings stores and the alert settings safety rule | — |
| `protocol/PROTOCOL-v2.md`, `protocol/PROTOCOL-identity.md` | the Nostr wire formats wearer ↔ followers | `:core:protocol`, `:share`, `SukraraShare` |
| `protocol/identity/*.json` | identity test vectors (keys, profile, avatar) — test-only keys | protocol tests on every app |
| `protocol/ogs/ogs-draft1.json` | Open Glucose Sharing draft test vectors | protocol tests on every app |
| `protocol/ble/default-mtu.json` (+ `generate.py`) | Bluetooth at ATT MTU 23: a Mac follower's HELLO frames (plain and as a long write) → the wearer's INFO frames, link named | `:share` `NearbyDefaultMtuTest`, `SukraraShare` `NearbyDefaultMtuTests` |
| `protocol/sync/` | owner → follower event sequences → expected follower state (see its README) | follower tests on every app |
| `treatments/treatment-form.json`, `treatments/SPEC.md`, `treatments/libreview-fixture.json` | the treatment form (kinds, units, limits) and the LibreView mapping | treatment UIs + `:integrations` tests |
| `export/libreview-csv.json` (+ `generate.py`, the reference writer) | CSV export cases: inputs → exact lines | `GlucoseCsvTest` (Android), `CoreTests` (Mac) |
| `pens/scenarios.json` | NovoPen dose engine scenarios | `:pen` tests |
| `sensor/traces/` | recorded sensor frames from open-source projects' test data (never the owner's sensor) | `:sensor` tests |
