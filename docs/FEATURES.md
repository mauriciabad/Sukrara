# Sukrara behaviour catalog

The features and exact behaviours Sukrara has, written so the final app (rebuilt from scratch) keeps every one of
them. One line per behaviour, with the rule and the numbers; the "why" and the history stay in the investigation
reports (`docs/NN-*.md`) and `docs/10-showcase.md`.

Rule: every change that adds or changes a behaviour updates this file in the same commit.

## Alerts

Rules: [spikes/alerts/SPEC.md](../spikes/alerts/SPEC.md); timelines: [spikes/alerts/scenarios](../spikes/alerts/scenarios/).

- Every change to the alert rules adds or updates a scenario in `spikes/alerts/scenarios/*.txt`; Android
  `AlertScenarioTest` and the Mac `-alerts-selftest` both run all of them and must pass (SPEC §7).
- Kinds and defaults (the user's settings): very low < 55 every 5 min (alarm volume), low < 70 every 5 min, high ≥ 180
  every 15 min, very high ≥ 250 every 10 min, extremely high ≥ 300 every 5 min (alarm volume), falling/rising fast
  2 mg/dL/min every 30 min (off), no data after 20 min every 60 min; other kinds at notification volume.
- Lows are strictly below the threshold (70 → alerts at 69); highs at or above it.
- "Very low" (id `very_low`) replaced "urgent low" everywhere (settings JSON, channels, sounds, events); no migration.
- Level alerts: one at a time, the most severe wins; a worse kind of the same side alerts at once; getting better
  (very low → low) doesn't alert.
- Rate alerts (falling/rising fast) run next to level alerts; no data runs on a timer and clears on the next reading.
- Readings older than 15 min never alert; the alert clears when the reading gets that old.
- Episodes: one per group and side; a brief exit (back in range for less than the repeat interval, at least 5 min)
  clears the notification but keeps the episode, its repeat count and its treatments; no new "first" alert.
- Repeats count from the last alert of the episode; repeat 0 = once per episode.
- Logging the answering treatment clears the alert; when its mute window ends and it still matches, it alerts at once
  and the repeats count from there.
- Carbs mute very low, low and falling fast (default 15 min); rapid insulin mutes high, very high and rising fast
  (default 60 min); extremely high and no data are never muted; window = `t ≤ now < t + min`; deleted entries don't count.
- Falling-high mute (default 20 min): no high/very high alert while `sgv + rate × min < high`; it only postpones (a
  repeat that came due fires when it ends).
- Headlines: "Very low glucose", "Low glucose", "High glucose", "Very high glucose", "Extremely high glucose",
  "Falling fast", "Rising fast", "No glucose data"; body "69 mg/dL ↘ · < 70 mg/dL".
- Headlines acknowledge treatments logged in the episode (or within the mute length before it): lows "· eat more"
  (1 carbs entry) / "· eat more again" (2+); highs "· still high after insulin" / "· still high after more insulin"
  (never advise more insulin). Counted even when the mute is off.
- The wearer's `alert` event carries `tr` (that count) so followers and the Mac show the same headline.
- Android timer: exact alarm (`USE_EXACT_ALARM`) at the engine's next check, so a mute end alerts on time between
  readings; Mac: one-shot timer at the next check plus the 60 s tick.
- Badge status (`AlertStatus`): None / Needed / Taken use the same thresholds and treatment windows as the engine.
- Notification channels: two per kind (`alert_n_<kind>`, `alert_a_<kind>`); any other `alert_*` channel is deleted.
- Default sound pack: Quest, a different variant each time, no per-kind packs; followers mirror the wearer by default.

## Treatments

## Graph and value card

## Settings

## Live notification

## Lock screen widget
