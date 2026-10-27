# contracts — what both V1 apps agree on

The cross-platform specs, schemas and fixtures that Sukrara V1's Android ([`apps/android`](../apps/android/)) and Mac
([`apps/macos`](../apps/macos/)) apps consume and are tested against, so the two never diverge.

**Change rule:** a change here lands with both implementations (and their tests) in the same change. `tools/check`
enforces it (parity groups `alert-scenarios`, `alert-spec`, `graph`, `settings`, `protocol-v2`, `identity`,
`treatments`, `export`; skip only with a `Parity-skip: <group>: <reason>` trailer) and CI runs both apps' tests when
anything here changes. **Alert scenario expectations never change without the owner's OK.**

Formats are JSON / TXT / MD (+ the alert sound files). No file here names app source paths, except
`graph/surfaces/surfaces.json` (the surface registry, checked by `tools/check`). The frozen PoC keeps its own copies
under [`poc/`](../poc/); V1 never reads those.

How the apps read these files: Android tests get the folder from the `sukrara.contracts` system property (convention
plugin `sukrara.contracts`, a Gradle input so caching sees changes); the Android alert sounds are a resource source
directory. Mac tests resolve `Contracts.url(…)` (env `SUKRARA_CONTRACTS`, else walking up from `#filePath`);
`apps/macos/Bundle/bundle.sh` copies the `.caf` sounds.

| Path | What | Read by |
|---|---|---|
| `alerts/SPEC.md` | the alert rules: kinds, thresholds, repeats, mutes, episodes, the alert settings JSON (§3) | `:core:alerts`, `SukraraAlerts` |
| `alerts/scenarios/*.txt` | timeline scenarios: event sequences → expected alerts (**byte-identical to the PoC's**) | `AlertScenarioTest` (Android), `AlertsTests` (Mac) |
| `alerts/copy.json` | alert texts (titles, mute pill, actions) both platforms must show | both alert notifiers + tests |
| `alerts/sounds/mac/*.caf`, `alerts/sounds/android/raw/*.ogg` | the four sound packs × eight kinds × three intensities (+ silence) | Mac bundle, Android `:monitor` resources |
| `graph/**` | the card / graph fixtures (layout scenes + rules + expectations, freshness, labels, sensor, clock, share, mark, place, status) | `:core:graph`, `SukraraGraph` tests |
| `graph/surfaces/surfaces.json` | THE registry of every place that shows the card or its value (V1 paths) | `tools/check` card rules, cross-surface tests |
| `settings/settings.json` | THE settings schema: every setting, its V1 store + key per platform, number formats, mirroring | `tools/settings/gen.py` → `SettingsSpec`, `SettingsSchemaTest` on both, `tools/check` |
| `settings/durations.json` | duration formatting cases ("1 h 30 min") | both `Durations` tests |
| `settings/README.md` | the PoC → V1 settings key table (for humans; no code reads it) | — |
| `protocol/PROTOCOL-v2.md`, `protocol/PROTOCOL-identity.md` | the Nostr wire formats wearer ↔ followers (V1 keeps them exactly, so a restored identity keeps its followers) | `:core:protocol`, `:share`, `SukraraShare` |
| `protocol/identity/*.json` | identity test vectors (keys, profile, avatar) — test-only keys | protocol tests on both |
| `protocol/ogs/ogs-draft1.json` | Open Glucose Sharing draft test vectors | protocol tests on both |
| `protocol/sync/` | owner → follower event sequences → expected follower state (see its README) | follower tests on both |
| `treatments/treatment-form.json`, `treatments/SPEC.md`, `treatments/libreview-fixture.json` | the treatment form (kinds, units, limits) and the LibreView mapping | treatment UIs + `:integrations` tests |
| `export/libreview-csv.json` (+ `generate.py`, the reference writer) | CSV export cases: inputs → exact lines | `GlucoseCsvTest` (Android), `CoreTests` (Mac) |
| `pens/scenarios.json` | NovoPen dose engine scenarios | `:pen` tests |
| `sensor/traces/` | recorded sensor frames from open-source projects' test data (never the owner's sensor) | `:sensor` tests |
