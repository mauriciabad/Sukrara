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

Not covered yet (needs the renderers to expose their layout): which mark labels fit, mute-line endpoints, slot order.
Next step: a layout dump from GraphGallery and the Mac `render` CLI, compared against the same files.
