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
- Notification body (Android + Mac): the value and arrow only (`69 mg/dL ↘`); falling / rising fast add the rate (`120 mg/dL ↓ · falling 2.4 mg/dL/min`); no data: `Nothing received for 20 min · last 120 mg/dL`. No threshold, no source ("from the wearer's phone").
- Android timer: exact alarm (`USE_EXACT_ALARM`) at the engine's next check, so a mute end alerts on time between
  readings; Mac: one-shot timer at the next check plus the 60 s tick.
- Badge status (`AlertStatus`): None / Needed / Taken use the same thresholds and treatment windows as the engine.
- Notification channels: two per kind (`alert_n_<kind>`, `alert_a_<kind>`); any other `alert_*` channel is deleted.
- One sound per alert: each channel rings only its Sukrara sound; the app restores its episodes after a restart (an
  update kills it) and shows the alerts that were up again without sound; the next sound is the normal repeat
  (Android + Mac: Mac UserDefaults `alertEpisodes`, restored at launch with the last stored reading; history/log line
  "resumed High (no sound)"; scenario event `restart`, SPEC §7).
- Default sound pack: Quest, a different variant each time, no per-kind packs; followers mirror the wearer by default.

## Treatments

- Amounts are written lowercase with no space before the unit everywhere (Android + Mac): `4.5u`, `18u`, `30g` (dialog suffixes, lists, chart labels, the slot; also the Nightscout API's long-acting note; the Mac's treatment rows and amount field).
- Short durations read `now` (< 1 min), `12m`, `1h 5m` (whole hours: `3h`), only hours from 6 h (`7h`), days from 48 h (`2d`); time left rounds up (`1m` until it is over).
- The home treatments list (wearer) and the follower's list end each row with the time since the entry (`12m`, `3h`), refreshed every 30 s. Mac: Glucose Report › Treatments rows end with the time of day and the same `12m` / `3h` (refreshed every 30 s while shown).
- The treatment dialog focuses carbs instead of rapid insulin when the latest fresh reading is below an enabled low-side alert threshold (`AlertStatus.level` in `Kind.LOW_SIDE`); otherwise rapid.

## Mac menu and windows

- A click on the menubar number opens the glance panel (a borderless, non-activating panel under the item, like `MenuBarExtra(.window)`: the menu material, 14 pt corners, a shadow); a second click, Escape, a click outside or another app taking focus closes it. Right-click (or Control-click) opens a short menu: Glucose Report…, Settings… ⌘,, Quit Sukrara ⌘Q.
- The panel, top to bottom: the glucose card in a recessed module (12 pt corners; black 18 % in dark mode, white 50 % in light mode, a separator outline with Increase Contrast); the quick-log field; *Glucose Report…* and *Settings…* as menu-like rows (hover highlight). No source, follow or error lines (Settings › General / Following show them), no treatment list (Glucose Report › Treatments).
- The card in the panel uses the shared palette without a background colour (no halo disc / marker hole) and a grid one step stronger (#4A4A4F dark, #C8C8CE light); with Increase Contrast secondary ink #D8D8DC / #3C3C40 and grid #7C7C82 / #8A8A90.
- No data: *No readings yet* in the module, *Follow Someone…* when not following, else "Waiting for the wearer's phone."; no quick-log row.
- Quick log (keyboard-first): the panel opens with the focus in *Log: 4.5u, 30g, 12 long, a note…*; Return logs and clears it. Grammar: a number (`,` or `.`), an optional unit — `u`/`iu` rapid (long when Long is selected), `g`/`carbs` carbs, `l`/`long` long, `rapid` — then an optional note; no unit → the selected kind; no number (or Note selected) → a note with the whole text; carbs whole grams, insulin one decimal; 0 is not an amount. The segmented Rapid · Carbs · Long · Note shows the kind the entry will get and sets the default for unitless numbers (⌘1–⌘4; remembered as `quickKind`). *Now ▾* sets 5–120 min ago (back to Now after each entry). The line under it previews the entry (`↩ 30g carbs · pasta`), and after logging shows *Logged 30g carbs · Undo* for 8 s (Undo deletes it). When the wearer hasn't allowed this Mac to log, the row is replaced by that sentence.
- Glucose Report window (panel row or right-click): a segmented *Report | Treatments* at the top (window title follows); Report: 1 day / 7 days / 14 days / 30 days (`reportDays`, default 14) and the report; Treatments: the last 7 days grouped by day (Today, Yesterday, weekday date), each row with the kind's symbol in its colour, amount, note, for entries made on this Mac a delivery symbol (clock = sending, paper plane = sent, ⚠︎ orange = not on the phone after 2 min, ✓ green = on the phone) whose tooltip says it in words ("Sent 14:05, waiting for Ana's phone", "On Ana's phone since 14:07"), then the time of day and the time since (`12m`); who logged it as the row's tooltip ("Logged on this Mac", "Logged on Ana's phone"); Delete in the context menu and ⌫ when this Mac may log. Position remembered (`SukraraReport`).
- Settings window (⌘, from the panel or the right-click menu): preference toolbar General · Graph · Alerts · Following, title = the pane's name, close only (minimize/zoom dimmed), 580 pt wide, each pane its own height (400 / 720 / 720 / 300 pt) keeping the top edge, reopens on the last pane (`settingsPane`) at the last position (`SukraraSettings`); changes apply at once.
- General: Open at login (`SMAppService.mainApp`; a note when it needs approval in Login Items or isn't the installed app), the data source and last reading (date, time, relative), the last poll error, **Reconnect Now** (restarts Wi-Fi discovery, relays and Bluetooth), the version, **Quit Sukrara**.
- Graph: time shown (3/6/12/24 h), time in range beside the graph, vertical lines; the lines across the graph (each with a remove button, an *Add a line at* field, 40–400 mg/dL, up to 6); beside the value: how many (0–3), each slot's kind, period and *Fainter*, what a ring's centre counts; ring order (↑/↓ per segment, Reset Order); last treatment: position, fallback kind, period, fainter; the six ranges as number fields with steppers (shown and drawn as `Bands.sane` keeps them: in order, low 40–120, high ≤ 300, very high ≤ 400, extremely high ≤ 450), Reset Ranges; Reset All Graph Settings… (confirmation). Same UserDefaults keys as before.
- Alerts: main switch and state line; alert source with its explanation; one row per alert (name, one-line summary such as `Below 70 mg/dL · repeats every 5 min · Quest · like an alarm`, Details…, switch); quiet highs / quiet lows as switches with minute steppers; sound pack and *Different sound each time*. Details… opens a sheet: the alert's switch, *Alert when* (threshold field + stepper with its unit, repeat every n min, 0 = once), sound (per-alert pack + ▶︎) and *Like an alarm*; Done (Return). Rules are read-only while mirroring the wearer (no data stays editable).
- Following: not following → a short explanation, **Follow from QR Image…** and **Follow with Invite Link…** (default button); following → the wearer's avatar and state, where entries logged here go, **Stop Following…** with a confirmation sheet.
- The app installs a main menu (Settings… ⌘,, Close Window ⌘W, Quit ⌘Q, Edit: Undo, Redo, Cut, Copy, Paste, Select All) so the standard shortcuts work in its windows and dialogs, although a menubar-only app never shows it.

## Readings and backfill

- Mac user-facing words never use protocol terms (v2, relay, roster, writer, epoch, BLE): the data source reads `Internet`, `Bluetooth · <phone>` or `Wi-Fi · Juggluco`; the follow state `Following Ana`; logs stay technical.
- Mac: every backfilled reading (v2 hour chunks over relays or Bluetooth, the BLE backlog, v1 Nostr, Juggluco's Wi-Fi history) goes into the 31-day readings store as it arrives, whatever its age; only the live 3 h working window is trimmed (a chunk older than 3 h before the newest reading used to be dropped, which left gaps in the graph after every launch, wake or reconnect).
- Mac follower (protocol v2): the chunk subscription starts 1 h before the newest stored reading, at least 13 h and at most 7 days back (the chunks' expiry); recomputed on every reconnect (wake, manual reconnect).
- Mac follower: 90 s after the chunk subscription starts (and again 90 s later) it checks the last 12 whole hours of the store; hours still empty while the stream is fresh (newest reading ≤ 15 min old) send one `history` request (`since` = the first empty hour) to the wearer over relays and Bluetooth, at most every 30 min (PROTOCOL-v2 "Missing history", as Android's follower).

## Graph and value card

- Treatment marks on every chart (Android + Mac: app, widgets, notification, lock screen, menubar panel, report window, Mac widgets) are labelled with the amount and the time since it (`4.5u 12m`), **under** the markers: in the time axis' row (an hour label that touches a treatment label is not drawn) and, when needed and the chart is ≥ 90 dp tall, a second row 11 dp under it (the plot shrinks by that row only then). Labels are never drawn over the glucose line.
- Treatment labels: entries ≤ 3 min apart (a meal, chained) or whose marker is < 9 dp from the group's first marker share one label, amounts in kind order (insulin, long-acting, carbs) joined by ` · ` plus the time since the newest (`4.5u · 45g 1h 35m`); notes get a marker only. Newest group first, each takes the first free place of: row 1 with its time, row 2 with its time, row 1 without, row 2 without; else no label (markers stay). A label may slide sideways while its marker stays ≥ 3 dp inside it; labels keep 4 dp apart and stay inside the chart's width.
- Treatment labels are laid out with a fixed per-character width model (`TextModel`, same table on both platforms), so Android (Roboto) and the Mac (SF Pro) make the same decisions; the side columns (% labels, values on the right) are sized by it too. Pinned by `spikes/graph-fixtures/labels/` (Android `GraphFixtureTest.labels`, Mac `-graphselftest`, which also checks the real SF Pro text fits every modelled box).
- Every treatment label has a 3 dp halo: its outline is erased from the chart (the chart is drawn in its own layer; `DST_OUT` / `.destinationOut`), so it reads over anything on any background (card, transparent widget, lock screen, notification colour, Mac material, tinted Mac widget).
- From each rapid / carbs mark a 2 dp line in its colour runs along the treatment lane to where its alert mute ends (`AlertStatus.muteUntil`: rapid + insulin mute, carbs + carbs mute); insulin's line sits 1.5 dp above the lane centre, carbs' 1.5 dp below. Ended: a vertical tick at the end. Still running: the line reaches the chart's right edge with a chevron. Long-acting, notes and kinds whose mute is off get no line.
- The card's big numbers are rounded on both platforms: the Mac uses SF Pro Rounded (value bold, ring / slot numbers heavy, their window labels semibold); Android uses Google Sans Flex with its roundness axis at full (ROND 100, opsz 18, weights 600 / 700 / 800, SIL OFL 1.1, bundled in `assets/fonts/` with `OFL.txt`) for the same texts, with proportional figures for the value (like the Mac): app panel (rolling digits), widgets, lock screen and the notification's value (drawn as a picture, since RemoteViews text can't use an app font).
- Status badge under the value (app panel, value/combined widgets, lock screen value; Mac dropdown and value/combined widgets) from `AlertStatus` (Mac: `AlertRules.swift`, the same functions the Mac AlertEngine uses): None → nothing; Needed → filled pill in the colour of what to log with its marker: `Eat carbs` (low side), `Take insulin` (high side, extremely high included); Taken → the same pill tonal (colour at ~24 % dark / 18 % light, text in the colour) with the "notifications paused" bell and the time left of the mute (`9m left`).
- The badge needs the value box to keep ≥ 26 dp of number after a row of 14–24 dp; otherwise it becomes an icon-only circle beside the arrow when there is room, else it is left out. A late reading's time sits after the badge on its row.
- Widgets redraw when the alert settings change (badge, mute lines and slot depend on them; Mac: the host writes its alert settings into the widget snapshot); the Android widget's description for screen readers includes the badge's words.
- Mac widgets in tinted rendering (desktop not focused: accented / vibrant, where the system keeps opacity or luminance and drops the hue) knock text out of filled shapes: the Needed pill is solid with its words and marker cut out (transparent); Taken stays a faint pill under solid words; the last-treatment slot gets a faint track under a solid mute arc, and the gap around the latest dot is cut out of the lines. Full-colour rendering is unchanged. Mac-only: Android widgets have no tinted mode.
- Last-treatment slot beside the value (Android + Mac; on by default, *Next to the number* = leftmost; *At the right end*; *Off*): kind marker, amount and time since, inside a ring in the kind's colour for the share of the alert mute left (shrinking towards 12 o'clock); empty grey track once the mute is over or when the kind's mute is off.
- The slot shows the treatment that mutes the current alert, else the newest rapid insulin (kept 4 h) or carbs (kept 3 h); a meal logged at the same minute shows the insulin. Long-acting insulin and notes never show.
- Without a recent treatment the slot shows its fallback (default: time in range, 30 days, faded 55 %; any slot kind and period, or nothing), only where the slot's data covers ≥ 25 % of its window.
- Slot priority when room is short: main ring, recent treatment, second ring, fallback, third ring. Slots and rings keep their designed size everywhere (app panel 68 dp; cards sized from their height); a wide value shrinks instead. The app panel measures the value (at least "388" + arrow), lets it shrink to 75 % (54 sp), then drops the lowest-priority slot (1–3, the main ring always stays); widgets and the Mac card fit as before (≤ 4).
- Settings `lastPlace` (`OFF` / `NEAR_VALUE` / `END`) and `lastElse` (`TIR:2592000:1`, `""` = nothing): Android in the graph settings JSON, Mac in UserDefaults keys of the same names and spellings, set in Settings → Graph → *Last treatment* (place, fallback kind, period, fainter).

## Sensor life

- The owner knows the current sensor's start, end and warm-up end (`core/SensorLife`, persisted): the direct source gives serial, start, end = start + the wear length from the NFC patch info (LE16 at +6, minutes; Libre 3 Plus 21600 = 15 d; 15 d when unknown or for a pairing saved before this field) and warm-up end = start + the patch info's warm-up (byte 13 × 5 min; 60 min default).
- Juggluco source: the start comes from Juggluco's `sgv.json?count=1&sensor=Y` (`sensor_status` `"dd-mm-yy HH:MM (…)"`, the phone's local time; serial = the entry's `_id` before `#`), read with every history fetch and again when the SSE `sensorId` changes; end = start + 15 d, warm-up 60 min (Juggluco publishes neither length).
- Sensor badge on the value card (Android app panel, widgets, lock screen value; Mac dropdown and widgets): right of the trend arrow (after the status badge's icon when that sits there), top-aligned with the number's caps, 9–14 dp tall (cap height × 0.24), in the secondary text colour: a small Libre sensor disc (circle + centre dot) whose outline is an arc depleting clockwise from 12 o'clock with the share of the sensor's life left (faint full track under it), then the time left: ≥ 24 h → whole days `13d`, ≥ 1 h → hours `20h`, else minutes `45m` (floor). Ended: the header turns into the ended text instead (below); `0m` in the very-low colour is the label's rule (fixtures). Left out when nothing is known or when it doesn't fit left of the rings. Screen readers (app): `Sensor: 13d left` / `Sensor ended`.
- Countdown ring: in the sensor's last `countdownHours` (1–24, default 8; Android: `SensorLife.setCountdownHours`, shared with the end-countdown notification; Mac: Settings → Graph → *Sensor countdown*, UserDefaults `countdownHours`), a ring over the main slot (the first ring; else the only slot) drains clockwise from 12 o'clock, full at N h left, empty at the end; same size as the slot ring (on its stroke's centre line), ink 0.3 × the ring's stroke on a background halo 0.62 × (black / white where the background is transparent, cut out in tinted Mac widgets), round caps. Also on the glucose notification's slots and the lock screen's slots. No ring when no slot is drawn.
- Warm-up (a new sensor, no reading since its start, until 15 min past the warm-up's end): the card shows the warm-up's time left where the number goes (minutes rounded up, `42m`, secondary colour, rounded bold), the sensor disc alone beside it filling up, and `Warming up · ready at 19:45` under it; the ring over the main ring fills 0 → 1 over the warm-up (the opposite of the countdown). The first reading ends it. Notification title `Warming up · ready at 19:45`; Mac menubar title `42m` (secondary colour, ticks every minute) with the same tooltip.
- Ended sensor: as soon as `SensorLife` says it has ended (end = `endEpoch`, pulled forward on an early end), the value header says `Sensor ended at 18:03` (very-low colour; `yesterday at 18:03`, `Sat 18:03` within the week, `12 Oct` older; calendar days in the local zone, `spikes/graph-fixtures/sensor/ended.json`) with `Replace the sensor` under it where it fits; a narrow box splits it into `Sensor ended` / `at 18:03`, a one-line box shrinks the first line. No value, arrow or badge any more (the last value stays greyed in the chart). Everywhere: app card, every widget size, lock screen, the chart's placeholder, the notification (title `Sensor ended at 18:03`, text `Replace the sensor`, no chip), the widget's screen-reader description, Mac menubar (title `Sensor ended` in the secondary colour, tooltip with the time; a timer switches it at the end), Mac dropdown and widgets. Not an error: `No reading since …` is never shown for an ended sensor.
- `no_data` alerts stay quiet while the sensor has ended or warms up (Android and Mac, wearer and followers); a running one clears; a warm-up that brings no reading alerts 15 min after its end (scenarios in `spikes/alerts/scenarios/other.txt`).
- Followers and the Mac get the sensor from the live events' `ss`/`se`/`sw` (PROTOCOL-v2 "Sensor life"): every reading's live event carries it, and the owner publishes a reading-less live event when the sensor changes and every 5 min while it warms up; the newest by `created_at` wins, one without `se` clears it. Mac: kept in UserDefaults (`sensorStart`, `sensorEnd`, `sensorWarmupEnd`) and in the widget snapshot (`sensor`).
- Debug builds: `DebugReceiver --es cmd sensor --el left <s> [--el len <s>] [--ei countdown <h>] [--ez clear true]` sets a synthetic sensor; the status JSON lists `sensor` (serial, start, end, label).
- Direct source, ended sensor (`direct/PatchState`; seen on the user's Libre 3 Plus: `state=5 currentLifeCount=21635`, then no glucose and a reconnect every 2 min for ever): the BLE patch state ≥ 5 (5 expired, 6 terminated, 7 error, 8 error terminated) or a life count ≥ the wear length marks the sensor ended (its end moves to now if earlier) and the source state reads `sensor ended` / `Replace the sensor, then scan the new one: Settings → Sensor`. States 6–8 stop at once; an expired sensor (5, or past its wear length) keeps the session for one glucose wait (120 s, a possible grace period) and then stops for good. Stopped = no reconnects (`ended:<serial>` in the direct prefs) until a new sensor is scanned; a reading that does arrive keeps it running.

## Sensor scan: other sensors

- The Sensor screen's NFC tap checks the chip first (UID maker byte): only 0x7A (Abbott, Libre 3 / 3 Plus) gets Libre 3 commands. 0x07 (Texas Instruments: Libre 1 / 2 / 2 Plus) gets no command at all and shows *Libre 2 sensors aren't supported* — start it with Juggluco, then pick Juggluco as the glucose source — with a *Glucose source* button; any other tag → *Not supported* / "not a FreeStyle Libre 3".

## Settings

- Settings pages are AOSP SettingsLib SPA (android17-release, vendored as `settingslib-spa`): SettingsTheme, SettingsScaffold, Category, Preference / SwitchPreference / TwoTargetSwitchPreference / MainSwitchPreference / CheckboxPreference, Footer, SettingsBanner, HighlightBox.
- A page opens with the large title (36 dp) expanded; scrolling collapses it into the 22 dp title of the top bar; the round tonal back button stays.
- Groups are 20 dp rounded cards (4 dp inner corners) 16 dp from the screen edges, 2 dp between rows, surfaceBright on a surfaceContainer page; group titles in primary.
- Rows: 72 dp min height, centred vertically, title 16 sp medium (500), summary 14 sp onSurfaceVariant, icons 24 dp in a 40 dp box tinted onSurfaceVariant; plain rows show no chevron.
- A row that both toggles and opens something (two targets) shows › then a 1 × 40 dp line, then the switch; switches show a check (on) / close (off) thumb icon.
- Overview category icons: the 24 dp glyph in the hue's tone 30 on the device's adaptive-icon shape (40 dp) in tone 90 (dark theme: 80), AOSP homepage colours.
- Page intros are plain body-large text (TopIntroPreference), no illustration.
- Choice dialogs are SPA ListPreference dialogs: choosing saves and closes.
- Every list of numbers ends with "Custom": it reveals a number field (unit shown, range under it); OK saves only a value in range; a custom value shows as "Custom" selected with its value, and round-trips through settings export / import.
- Custom ranges: Nostr interval 1–120 min, LibreView interval 1–240 min, alert reminders 1–240 min, thresholds 40–400 mg/dL, rates 0.5–10 mg/dL/min (one decimal), no data 5–240 min, quiet after insulin 10–360 min, falling / carbs 5–120 min, graph windows 1–48 h, ring periods 1–90 days.
- Alerts page: main switch, then one two-target row per alert (switch = on/off, row = the alert's page), quiet rules, sounds.
- Each alert has its own page: main switch "Use <alert> alert", Alert when (threshold, remind), Sound (sound, volume), Test (play sound, Play with vibration, send a test alert, notification category). Followers mirroring the wearer see threshold and reminders read-only ("Set by the wearer's app").
- Every ▶ (sound pack list, an alert's sound list, Play sound) also plays that alert's vibration (AlertHaptics, alarm or notification usage) unless "Play with vibration" is unticked; the check box is a per-phone UI preference, on by default, and only affects previews.

## Live notification

- Chip off (or Live Updates unavailable): a colorized foreground-service card (background = range colour, ink black or white by contrast) with a custom layout inside the system's decoration.
- Collapsed (≤ 48 dp): value 30 sp + arrow, the reading's time only once it's late, and the slots (same choice as the home widgets: `GraphConfig.slots`, ring count, last-treatment slot and fallback; `appSlots` → `Slots.arrange`) as 42 dp discs at the end.
- Expanded: value 44 sp + arrow + late time + slots as 52 dp discs on one row; below it the graph (notification window, default 12 h) at the content column's full width, 96 dp tall, on a rounded surface (surface container high, light/dark), scaled uniformly (never stretched).
- Slot discs are filled with the same surface colour as the graph panel, so rings read on every range colour; as many as fit beside the measured value (at least "388") and arrow (≤ 4).
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

## Sensor reminders and countdowns

Wearer only (`sensorend/`; rules in `SensorEndPlan`, tests `SensorEndPlanTest`); followers get none of these. Settings → **Sensor end**.
- Reminders: a list of (days before the end day 0–3, time of day), at most 6; default *1 day before at 09:00* and *on the end day at 09:00*. Each fires in the phone's time zone on (end day − days) at its time, only if that is after the sensor's start and before its end (a sensor ending at 07:30 skips "end day 09:00"). Text: *Sensor ends today / tomorrow / on Wednesday at 14:30* (≥ 7 days: the date) · *Have a new sensor ready.* One notification slot: the later reminder replaces the earlier one.
- Reminders that passed before Sukrara knew them (new sensor, edited list) never fire; one missed while the phone was off fires late if the sensor hasn't ended (of several, only the latest).
- End countdown (default on): an ongoing ProgressStyle notification from end − *Countdown starts* (`SensorLife.countdownHours`, 1–24 h, default 8, shared with the card's countdown ring) to the end: *Sensor ends at 14:30* · *Have a new sensor ready to replace it.*, the bar = time left in the window (empties), a timer tracker, a chronometer counting down to the end, a *Hide* action. Silent channel, refreshed every 15 min (non-waking).
- It is promoted (Live Update chip with the timer) only while the glucose chip is off: the status bar shows one chip and the countdown would take it from the glucose value for hours. With the glucose chip on it stays in the shade and lock screen. On Android 16 without QPR2 (API 36.0, no promoted notifications) both countdowns are plain ongoing notifications (the promotion calls are tried, never required; `SensorEndNotifyTest` posts both on Robolectric's 36.0).
- Warm-up countdown (default on): from a new sensor's start until its first reading (`SensorInfo.warmupEndEpoch`, 60 min), always promoted: *Sensor warming up* · *Readings at 19:45*, the bar fills up (`SensorLife.warmupFraction`), the chip counts down; refreshed every 5 min. Gives up 15 min after the warm-up end without a reading (`SensorLife.WARMUP_GRACE_S`). The first reading replaces it with *Sensor ready* · *Readings have started.* (sound, gone after 10 min), only if the warm-up countdown was shown and within 1 h of that reading.
- Swiping away or *Hide* keeps a countdown away for that sensor (Live Update guideline: don't repost a dismissed update); the warm-up one then also skips *Sensor ready*.
- At the end (or when the sensor reports it ended early, end moved to now): *Sensor ended* · *Replace it to keep getting readings.* once, on the reminders channel, at most 12 h after the end; a new sensor clears it.
- Channels: *Sensor reminders* (default importance, sound) for reminders / ended / ready; *Sensor countdowns* (default importance so it shows on the lock screen, no sound or vibration) for both countdowns. Not the alert channels.
- Scheduling: one AlarmManager alarm at a time for the next moment that matters, re-planned on every alarm, new sensor or end change, settings change, boot, app update, clock and time-zone change. Reminders, window start, warm-up give-up and end: `setExactAndAllowWhileIdle` (Sukrara already holds `USE_EXACT_ALARM` for alerts; 10-min window if exact alarms are unavailable); progress refreshes: plain non-waking `set`.
- Settings export / import / reset include the reminders, both switches and the countdown hours.

## Languages

- English (default) and Spanish (Spain) everywhere, settings pages included (every page, alert page, dialog, search and the alerts page's "lately" list); the phone's language picks one, or Settings → Apps → Sukrara → Language (per-app language, Android 13+).
- Settings search finds rows in the shown language, accents optional ("insulina rapida" finds "Tras insulina rápida"): the index is built from the pages as they are drawn.
- Settings words follow Android's own Spanish where a stock setting exists: Ajustes, Activado / Desactivado, Pantalla de bloqueo, Barra de estado, No molestar, Restablecer, Buscar en Ajustes.
- Text lives in `res/values/strings.xml` and `res/values-es/strings.xml`; a unit test fails when a key or a format argument is missing in a translation.
- Numbers, dates and times follow the chosen language (decimal comma in Spanish, localized day and month names); units stay as they are (mg/dL, `4.5u`, `30g`).
- Spanish terms: hidratos (carbs), insulina rápida / lenta, tiempo en rango, seguidores; alert headlines like "Glucosa baja · come más".
- Changing the language renames the notification channels and redraws the widgets, the notification and the lock screen widget at once.
- Alert rules and their timeline scenarios stay in English: the engine reports the kind and what was logged, and the notification and the settings pages word them. The log and the debug dump stay in English.
