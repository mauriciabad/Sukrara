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

Not covered yet: mute-line endpoints, slot order.
