# Graph fixtures (shared by Android and Mac)

"One graph everywhere" as data: each `*.json` here holds `cases` that both renderers must decide the same way.

- Android: `GraphFixtureTest.kt` (`./gradlew :app:testDebugUnitTest --tests '*GraphFixtureTest*'` in spikes/showcase-android)
- Mac: `Sukrara -graphselftest` (`swift build && .build/debug/GlucoseBar -graphselftest` in spikes/menubar, run inside the repo)
- CI runs both (`.github/workflows/checks.yml`); `tools/check` reminds you to add a case when the card code changes.

A case: `name`; optional `settings` (alert settings JSON, spikes/alerts/SPEC.md §3, missing keys = defaults);
`reading` `{t, sgv}`; `treatments` `[{id, k: rapid|long|carbs|note, a, t, n?}]`; all times are seconds relative to
"now" (negative = past). `expect`:

| key | meaning |
|---|---|
| `paused` | optional: the alert notification's Mute in force, side (`low`, `high`) → its end in seconds from now |
| `status` | `none`, `needed:<kind>`, `taken:<kind>:<treatment id>` or `paused:<kind>` (AlertStatus) |
| `until` | for `taken`: end of the mute, seconds from now |
| `badge` | `null` or `{kind, text, short, tonal}` (StatusBadge.look, English words) |
| `last` | `null` or `{id, until, with?}` (LastTreatment.of; `until` null = its kind mutes nothing; `with` = the carbs drawn with running insulin, `{id, until}`, absent = none) |
| `amounts` | `{id: "4.5u" \| null}` (TreatmentMarks.amount) |

## Treatment label layout (`labels/*.json`)

The layout dump: which treatment labels are drawn, where and with what text (rules: docs/16-graph.md, "Treatment
labels"). Run by `GraphFixtureTest.labels` (Android) and `-graphselftest` (Mac), which also checks that SF Pro's real
width of each label fits the width model. A case: `name`; `chart` `{w, h}` (the chart's area, dp); `hours` (window);
`pct` (time in range has data, so the % column shows where the chart is ≥ 240 dp wide); `treatments` as above
(`k`: rapid | long | carbs | note; times relative to "now"). Optional `marks` (Treatments on the graph): absent = the defaults (carbs: point
only), `"all"` = every part of every kind, or `{kind: [point, line, amount, time]}` (missing kinds = their default). The file's `pctLabel` and `valueLabel` stand in for the widest
% label and the widest value on the right (dp; the real ones are measured with the drawing font): each side column is
it plus the gap of `layout/rules.json` on each side. `expect`:

| key | meaning |
|---|---|
| `plot` | `[left, right]` of the plot, dp from the chart's left (± 0.5) |
| `lanes` | label rows the chart allows (1, or 2 from 90 dp tall) |
| `labels` | every label left to right: `ids` (its entries in label order), `text`, `lane` (0 = the axis row, 1 = under it), `x` (its centre, dp, ± 0.5) |
| `dropped` | the groups left without a label (entry ids), in placing order |

Positions are rounded to 0.5 dp; Android computes in `Float`, the Mac in `Double`. To add a case, write it with an
empty `expect`, run `GraphFixtureTest` (`--rerun`: Gradle doesn't see the fixture files as inputs), check the dump in
the failure message against the rules, paste it, and run the Mac self-test.

## Where the sensor badge goes (`place/place.json`)

One decision for every surface (docs/FEATURES.md, "Sensor badge"): `GlucoseCard.sensorSpot` on both platforms, the
badge's box in the chart and the room it keeps free of treatment labels. Run by `GraphFixtureTest.place` (Android) and
`-graphselftest` (Mac). `rooms`: `{label, room}` = `GlucoseGraph.sensorWidth(label)` in the `TextModel` (dp, ± 0.05).
A case: `name`; `card` `{graph, value, axisLabels?, sensorTime?}` (the WHOLE card, also for surfaces drawn in parts);
`sensor` `live` (13 days left) | `warmup` | `ended` | `none`; `age` (the newest reading's, s); optional `chart`
`{w, h}`. `expect`: `spot` `graph` | `none` (no badge beside the number: the user, 2026-10-21), and with a chart `box` `[x, top, h]` (dp, ± 0.05; no second
label lane). `tools/check` (`graph-surface`) makes every surface go through this decision.

A label case (above) may carry `sensor` (the badge's label): lane 0 starts with its room taken.

## Sensor life (`sensor/*.json`)

The sensor badge and the countdown ring (docs/FEATURES.md, "Sensor life"). Run by `GraphFixtureTest.sensor` (Android)
and `-graphselftest` (Mac). A case: `name`; `start`, `end` (the sensor's, seconds relative to "now"); `countdownHours`
(the window); `latest` (the newest reading's time, `null` = none); optional `warmup` (the warm-up's end; default
start + 60 min). `expect`:

| key | meaning |
|---|---|
| `label` | the badge text, English words: ≥ 24 h left → whole days (`13d`), ≥ 1 h → hours (`20h`), else minutes (`45m`, `0m` once ended) |
| `ended` | `now ≥ end`: badge in the alert colour, "Sensor ended" once readings stop |
| `life` | the disc's arc: share of the sensor's life left, 1 → 0 (± 0.001) |
| `warmup` | while warming up (no reading since the start, ≤ 15 min past the warm-up's end) the card's figure, minutes rounded up (`42m`), from 60 of them hours (`1h`: one rounding, so 59:15 left is `1h`), `Almost ready` from the warm-up's end until the first reading (never `0m` or less); `null` = not warming |
| `pill` | ended → the action pill under "Sensor ended", `{kind: "sensor", text, short, tonal}` (StatusBadge.replaceSensor, English words); else `null` |
| `ring` | the ring over the main ring: warming → the warm-up done (fills 0 → 1); else the countdown, share of the window left; `null` = no ring (more than the window left, or ended) (± 0.001) |

## Clock (`clock/*.json`)

Clock times in the device's 12/24-hour setting rather than the locale's default, and the graph's hour labels
(docs/FEATURES.md, "Languages"). Run by `GraphFixtureTest.clock` (Android, `graph/Clock.kt`) and `-graphselftest`
(Mac, `Clock`). A case: `name`; `locale` (BCP 47); `minute` (of the day, UTC); `hour24` (the setting). `expect`:
`time` (the clock time, e.g. `9:08 PM`) and `axis` (the hour label, `21:00` / `9 PM`). U+00A0 and U+202F compare as
plain spaces.

Not covered yet: mute-line endpoints.

## Time shares against a goal (`share/*.json`)

How a time-in-range share is written next to its goal (report card / Mac report rows, the rings' number, the
coverage note). Run by `GraphFixtureTest.share` (Android, `ShareText` in graph/GlucoseMetrics.kt) and `-graphselftest`
(Mac, `ShareText` in SukraraGraph.swift). A case: `name`; `x` (the share, 0..1); `goal` (`veryHigh`, `above`,
`inRange`, `below`, `veryLow`, `coverage` = at least 70 %, or `null`); `locale`; `expect` (the number without "%").
Rule: whole percent from 10 %, one decimal under 10 %, and more decimals (up to two) whenever the rounded number would
be on the other side of the goal than the value; a non-zero share that rounds to 0 is `<0.1`.


## Time-in-range marks (`mark/*.json`)

The ring's crown / laurel and the % column's crown (docs/FEATURES.md, "Time-in-range marks"). Run by
`GraphFixtureTest.mark` (Android) and `-graphselftest` (Mac). A case: `name`; `tir` (`veryLow`, `low`, `tight`, `target`,
`high`, `veryHigh` shares, missing = 0, and `coverage` of the window); `range` (`inRange` | `tight`). `expect`:

| key | meaning |
|---|---|
| `mark` | `none`, `laurel` (≥ 90 % as shown) or `flowers` (≥ 95 %), by the share alone, or `crown` (nothing outside the range); all only with ≥ 70 % coverage |
| `text` | the ring's number (ShareText: never `100` unless it is all) |
| `column` | `[below, in range, above]` as the % column shows them (largest remainder; in range is 100 only when it is all) |
| `crown` | whether the column's 100 wears the crown |
| `flower` | the laurel's flower (`Bloom`): `none`, `lavender` (95), `forget_me_not` (96), `daisy` (97), `cherry` (98), `rosette` (99 and the crowned 100) |

## Freshness steps (`freshness/freshness.json`)

What every glucose surface shows as the latest reading ages (90 s time, 5 min struck + grey + "no trend" ring, 20 min
"no readings" icon, 3 h no chart), through the one shared `Freshness.look` per platform. The file also holds the
**surface registry** (`surfaces.android` / `surfaces.mac`: id → files): `FreshnessFixtureTest` (Android, Robolectric on
JDK 21) and `-graphselftest` (Mac) render every case through one adapter per surface id and fail when a listed surface
has none; `tools/check` `freshness-surface` scans the listed files (no value formatted outside the look, no own
thresholds). A new glucose surface: add it to the registry, write its adapter on its platform. `steps` pins the redraw
times (`Freshness.nextChangeAt`), which `FreshnessFixtureTest.night` replays with Android's alarm.

## Surfaces and layout across surfaces (`surfaces/surfaces.json`, `layout/*.json`)

"Things like this cannot happen again" (the user, 2026-10-21, after a badge move reached one surface only).

- `surfaces/surfaces.json` is THE registry of every place that shows the card or its value, per platform: `id` (the
  `CardSurface` enums list exactly these), `kind` (`card`: draws through `GlucoseCard.draw` / `image`; `glyph`: text and
  icons the system lays out, from `GlucoseCard.face`), `files`, `entry` (the real entry path the tests call), `parts`
  and `sizes` (card surfaces); plus the renderer's `owners`, the `api` a surface may use of the renderer's namespaces,
  and `value_text_ok` (files that write a glucose value as data or event text). `tools/check` (card-surface, card-api,
  card-text) holds the code to it.
- `layout/scenes.json`: the scenes (synthetic readings by integer maths, treatments, sensor states; `now` fixed, UTC)
  and settings variants every surface is rendered with.
- The renderer reports what it laid out (`CardLayout`: element, box, what it shows; `(no room)` = decided but left out
  for room, `[...]` = how it was placed). `CardLayoutTest` (Android) and `LayoutSelfTest` (Mac, in `-graphselftest`)
  render every registered surface through its real entry path at each size, for every scene and settings variant.
- `layout/rules.json`: for each rule, the elements and the facts (`by`) they may depend on: all renders with the same
  facts must show the same elements (the surface is never a fact). `mode: subset`: the shown slots must be one priority
  order cut at different lengths. `box`: positions relative to the chart's bottom-left count too.
  `gaps`: a column whose margins are one constant: from the `column` element's box to the boxes of its `labels`
  elements (the text as drawn, measured with the drawing font) there are exactly `gap` dp on the `align` side (to
  each label) and on the other side (to the widest), whatever the surface, size, labels or platform.
- `layout/rules.json` `distribution`: the distribution's dot pitch, dot radius and the outline erased around the dots
  (`cut`, dp); both renderers' constants must equal them and every drawn distribution's erased box
  (`chart.distribution.cut`) is its dots' box (`chart.distribution.dots`) plus `cut` on the right and the top.
- `layout/scenes.json` `scroll`: scrolled charts (the app panel, the Mac dropdown), each case a scene and how far back
  (`back`, s) the window ends, rendered through the scrollable surfaces' entry paths (`PanelCard` with a `ChartScroll`,
  `CardView.scrollEnd`): the chart must equal the chart of the visible data alone (every reading and treatment outside
  the window removed, now = the window's end), its y range (`chart.y`) the visible readings', the Now pill
  (`chart.now`) only when scrolled, and `expect` (font-independent: `chart.pct`, `chart.y`, `chart.window`,
  `chart.markers`) on both platforms.
- `layout/expect.json`: the shared rules' answers, generated by `CardLayoutTest` with `-Dlayout.write=true` (review the
  diff: it is where Android and the Mac agree); the Mac checks its own surfaces against it.
- Facts: `scene`, `settings`; `face` (the surface shows a value: a card with one, or a glyph surface), `value`
  (`true` / `false` for cards, `glyph`), `graph`, `labels`, `side`, `hours`, `sensorTime`, `badge` (the card style),
  `rings` (`KIND:window:RANGE:marks,…|lastPlace|lastElse`), `chart` (`WxH` dp), `pctWide` (chart ≥ 240 dp), `lane2`,
  `sensorShown`, `part` and `box` (the part and box the slots were laid out in).
