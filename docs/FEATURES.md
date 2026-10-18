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

- Amounts are written lowercase with no space before the unit everywhere (Android + Mac): `4.5u`, `18u`, `30g` (dialog suffixes, lists, chart labels, the slot; also the Nightscout API's long-acting note; the Mac's treatment rows and amount field).
- Short durations read `now` (< 1 min), `12m`, `1h 5m` (whole hours: `3h`), only hours from 6 h (`7h`), days from 48 h (`2d`); time left rounds up (`1m` until it is over).
- The home treatments list (wearer) and the follower's list end each row with the time since the entry (`12m`, `3h`), refreshed every 30 s. Mac: the menu's *Treatments (24 h)* rows end with ` · 12m` (refreshed every 15 s while the menu is open).
- The treatment dialog focuses carbs instead of rapid insulin when the latest fresh reading is below an enabled low-side alert threshold (`AlertStatus.level` in `Kind.LOW_SIDE`); otherwise rapid.

## Graph and value card

- Treatment marks on every chart (Android + Mac: app, widgets, notification, lock screen, menubar dropdown, Mac widgets) are labelled with the amount and the time since it: `4.5u 12m`; entries ≤ 3 min apart (a meal) show the time once; a label that doesn't fit with its time is placed without it, then dropped.
- From each rapid / carbs mark a 2 dp line in its colour runs along the treatment lane to where its alert mute ends (`AlertStatus.muteUntil`: rapid + insulin mute, carbs + carbs mute); insulin's line sits 1.5 dp above the lane centre, carbs' 1.5 dp below. Ended: a vertical tick at the end. Still running: the line reaches the chart's right edge with a chevron. Long-acting, notes and kinds whose mute is off get no line.
- Status badge under the value (app panel, value/combined widgets, lock screen value; Mac dropdown and value/combined widgets) from `AlertStatus` (Mac: `AlertRules.swift`, the same functions the Mac AlertEngine uses): None → nothing; Needed → filled pill in the colour of what to log with its marker: `Eat carbs` (low side), `Take insulin` (high side, extremely high included); Taken → the same pill tonal (colour at ~24 % dark / 18 % light, text in the colour) with the "notifications paused" bell and the time left of the mute (`9m left`).
- The badge needs the value box to keep ≥ 26 dp of number after a row of 14–24 dp; otherwise it becomes an icon-only circle beside the arrow when there is room, else it is left out. A late reading's time sits after the badge on its row.
- Widgets redraw when the alert settings change (badge, mute lines and slot depend on them; Mac: the host writes its alert settings into the widget snapshot); the Android widget's description for screen readers includes the badge's words.
- Last-treatment slot beside the value (Android + Mac; on by default, *Next to the number* = leftmost; *At the right end*; *Off*): kind marker, amount and time since, inside a ring in the kind's colour for the share of the alert mute left (shrinking towards 12 o'clock); empty grey track once the mute is over or when the kind's mute is off.
- The slot shows the treatment that mutes the current alert, else the newest rapid insulin (kept 4 h) or carbs (kept 3 h); a meal logged at the same minute shows the insulin. Long-acting insulin and notes never show.
- Without a recent treatment the slot shows its fallback (default: time in range, 30 days, faded 55 %; any slot kind and period, or nothing), only where the slot's data covers ≥ 25 % of its window.
- Slot priority when room is short: main ring, recent treatment, second ring, fallback, third ring. The app panel fits `(width − 188 dp) / 76 dp` slots (1–3); widgets and the Mac card fit as before (≤ 4).
- Settings `lastPlace` (`OFF` / `NEAR_VALUE` / `END`) and `lastElse` (`TIR:2592000:1`, `""` = nothing): Android in the graph settings JSON, Mac in UserDefaults keys of the same names and spellings, set from the menu's Graph → *Last treatment* submenu (place, fallback kind, window, fainter).

## Settings

## Live notification

- Chip off (or Live Updates unavailable): a colorized foreground-service card (background = range colour, ink black or white by contrast) with a custom layout inside the system's decoration.
- Collapsed (≤ 48 dp): value 30 sp + arrow, the reading's time only once it's late, and the slots (same choice as the home widgets: `GraphConfig.slots`, ring count, last-treatment slot and fallback; `appSlots` → `Slots.arrange`) as 42 dp discs at the end.
- Expanded: value 44 sp + arrow + late time + slots as 52 dp discs on one row; below it the graph (notification window, default 12 h) at the content column's full width, 96 dp tall, on a rounded surface (surface container high, light/dark), scaled uniformly (never stretched).
- Slot discs are filled with the same surface colour as the graph panel, so rings read on every range colour; as many as fit beside ≈ 110 dp of value (≤ 4).
- The follower/state line ("1 follower · 1 nearby") is the header's sub text beside "Sukrara", not a row of its own.
- Android 12+ keeps custom content in the column right of the icon and clips it there (a −52 dp margin was cut off on API 36; Android 17's column starts at 72 dp): nothing can be drawn under the icon, so the card is kept short instead.
- Pictures (graph + slots) are cached per reading / 5-min tick / theme / treatments / graph and alert settings, and not redrawn while the screen is off.
- Chip on (Android 17 Live Update, MetricStyle): metrics = value (mg/dL, semantic colour by range), then the slots as text (`86%` · `in range 1d`, `6.7%` · `GMI 14d`, `4.5u` · `12m ago`), three metrics in all; once the reading is late its time takes the last one. The trend is the chip's arrow and the title's word.

## Lock screen widget

- Background (Settings → Lock screen → Background, `lockBg`): None, Shadow, Dim, Card, **Frosted glass** (`frosted`, default): the wallpaper blurred (24 dp, SystemUI's BackgroundBlurDrawable) and tinted; falls back to the card when cross-window blur is off. Unknown values fall back to Frosted glass.
- Appearance (`lockLook`): **Automatic** (default, follows the system dark theme), Light, Dark.
- Dark: the widget's dark palette; frosted tint black 35 %, card = surface container high dark 88 %, dim = black gradient (0 → 30 % → 60 %), shadow = black 60 %.
- Light: the home widgets' look; light palette, frosted tint = Glance's widget background (`system_accent2_50`, Material You, near white) 60 %, card = the same colour 88 %, dim = white gradient, shadow = white glow 70 %.
- A dark-theme flip redraws at once: the module re-tints on its view's configuration change, the app redraws the widgets from a `ComponentCallbacks` night-mode watcher.
- Slots right of the lock icon use the same choice as the home widgets (`appSlots`: rings, report numbers, last treatment, fallback), main rightmost, as many as fit (≤ 4).
