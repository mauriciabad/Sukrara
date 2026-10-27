# Sukrara V1 architecture

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
