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
| `status` | `none`, `needed:<kind>` or `taken:<kind>:<treatment id>` (AlertStatus) |
| `until` | for `taken`: end of the mute, seconds from now |
| `badge` | `null` or `{kind, text, short, tonal}` (StatusBadge.look, English words) |
| `last` | `null` or `{id, until}` (LastTreatment.of; `until` null = its kind mutes nothing) |
| `amounts` | `{id: "4.5u" \| null}` (TreatmentMarks.amount) |

## Treatment label layout (`labels/*.json`)

The layout dump: which treatment labels are drawn, where and with what text (rules: docs/16-graph.md, "Treatment
labels"). Run by `GraphFixtureTest.labels` (Android) and `-graphselftest` (Mac), which also checks that SF Pro's real
width of each label fits the width model. A case: `name`; `chart` `{w, h}` (the chart's area, dp); `hours` (window);
`pct` (time in range has data, so the % column shows where the chart is ≥ 240 dp wide); `treatments` as above
(`k`: rapid | long | carbs | note; times relative to "now"). `expect`:

| key | meaning |
|---|---|
| `plot` | `[left, right]` of the plot, dp from the chart's left (± 0.5) |
| `lanes` | label rows the chart allows (1, or 2 from 90 dp tall) |
| `labels` | every label left to right: `ids` (its entries in label order), `text`, `lane` (0 = the axis row, 1 = under it), `x` (its centre, dp, ± 0.5) |
| `dropped` | the groups left without a label (entry ids), in placing order |

Positions are rounded to 0.5 dp; Android computes in `Float`, the Mac in `Double`. To add a case, write it with an
empty `expect`, run `GraphFixtureTest` (`--rerun`: Gradle doesn't see the fixture files as inputs), check the dump in
the failure message against the rules, paste it, and run the Mac self-test.

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
| `warmup` | while warming up (no reading since the start, ≤ 15 min past the warm-up's end) the card's figure, minutes rounded up (`42m`), `Almost ready` from the warm-up's end until the first reading (never `0m` or less); `null` = not warming |
| `pill` | ended → the action pill under "Sensor ended", `{kind: "sensor", text, short, tonal}` (StatusBadge.replaceSensor, English words); else `null` |
| `ring` | the ring over the main ring: warming → the warm-up done (fills 0 → 1); else the countdown, share of the window left; `null` = no ring (more than the window left, or ended) (± 0.001) |

## Clock (`clock/*.json`)

Clock times in the device's 12/24-hour setting rather than the locale's default, and the graph's hour labels
(docs/FEATURES.md, "Languages"). Run by `GraphFixtureTest.clock` (Android, `graph/Clock.kt`) and `-graphselftest`
(Mac, `Clock`). A case: `name`; `locale` (BCP 47); `minute` (of the day, UTC); `hour24` (the setting). `expect`:
`time` (the clock time, e.g. `9:08 PM`) and `axis` (the hour label, `21:00` / `9 PM`). U+00A0 and U+202F compare as
plain spaces.

Not covered yet: mute-line endpoints, slot order.

## Time shares against a goal (`share/*.json`)

How a time-in-range share is written next to its goal (report card / Mac report rows, the rings' number, the
coverage note). Run by `GraphFixtureTest.share` (Android, `ShareText` in graph/GlucoseMetrics.kt) and `-graphselftest`
(Mac, `ShareText` in SukraraGraph.swift). A case: `name`; `x` (the share, 0..1); `goal` (`veryHigh`, `above`,
`inRange`, `below`, `veryLow`, `coverage` = at least 70 %, or `null`); `locale`; `expect` (the number without "%").
Rule: whole percent from 10 %, one decimal under 10 %, and more decimals (up to two) whenever the rounded number would
be on the other side of the goal than the value; a non-zero share that rounds to 0 is `<0.1`.

