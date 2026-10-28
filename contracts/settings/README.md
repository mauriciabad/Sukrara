# Settings schema

[`settings.json`](settings.json) is THE settings schema of every Sukrara app (its `_about` explains every field).
`python3 tools/settings/gen.py` turns it into `SettingsSpec.kt` (`:core:data`) and `SettingsSpec.swift`
(`SukraraCore`): number formats, option lists, the inventory, and the store / key constants. App code takes every
stored key from the generated `SettingsSpec` (tools/check rule `settings` fails on a key written by hand).
[`durations.json`](durations.json) holds the duration formatting cases every `Durations` formatter passes.

## Stores

- **Android:** one Preferences DataStore file per area: `settings` (general), `graph`, `alerts`, `sensor`, `sharing`,
  `integrations`, `widgets`, `pens`, `statusbar`, `lock`. Complex values (lists, per-place maps) are JSON strings
  under their key.
- **Mac:** `defaults` = the app's UserDefaults (App Group suite where the widget reads it), `system` = macOS state
  (`SMAppService.mainApp` for Open at login).
- **Alerts:** ONE key `alerts` per platform holding the JSON object of [`../alerts/SPEC.md`](../alerts/SPEC.md) §3.
  The schema's `alerts.*` entries address its fields with `path` (`{kind}` = each kind). Safety rule for every app: if
  the stored value can't be decoded, the app keeps running with the **last value it decoded** (in memory, else the
  SPEC defaults with alerts ON), shows a visible "Alert settings couldn't be read" error with a way to reset them,
  and never turns alerts off or drops a threshold silently. Each app's alert-settings store has a test for it.
