# Sukrara V1: how it was built

On the night of 2026-10-27 the Sukrara showcase PoC (0.11.x, now frozen in [`poc/`](../../poc/)) was rebuilt as V1:
a clean Android app ([`apps/android`](../../apps/android/)), a clean Mac app ([`apps/macos`](../../apps/macos/)) and
the specs and fixtures both are tested against ([`contracts/`](../../contracts/)). New app ids (`app.mauri.sukrara`),
no data migration, first release 0.90.0 as a GitHub prerelease (release candidate). The work was done by an
orchestrator agent and many chunk agents, each in its own git worktree, merged through the integration branch `next`.

This folder is the record of how. What came into V1 and what was left out is in
[docs/10-showcase.md › V1](../10-showcase.md#v1-what-came-in-and-what-was-left-out); what the apps do is in the
[README](../../README.md) and [FEATURES.md](../FEATURES.md).

| File | What it is |
|---|---|
| [plan.md](plan.md) | The planner's V1 plan: repo layout, Android and Mac architecture, `contracts/`, priorities (§5), chunk DAG, gates, release plan, risks |
| [d1-change.md](d1-change.md) | The owner's decision that overrides the plan's D1: new ids, clean names, no migration, first-run setup, side by side with the showcase, own release key, version 0.90.0 |
| [changes.md](changes.md) | Plan amendments after the two plan critiques (P1–P10 product/safety, A1–A10 architecture); they override plan.md |
| [dag-v2.md](dag-v2.md) | The authoritative chunk table (priority, effort, whether a G0 critic is required, dependencies) |
| [process.md](process.md) | The methodology every agent followed and how a chunk agent works (G0 plan → G1 build → G2 review → G3 merge) |

## The review gates that ran

- **Plan critique (before any code).** Two independent critics reviewed plan.md. Product / safety: *REVISE*, with
  two safety blockers in the switch-over journey (take over the sensor with the LibreView account receiver ID so the
  owner can go back; restore followers only after the sensor runs in V1) and showcase silencing against double
  alarms. Architecture: *APPROVE WITH CHANGES* (re-tiering, scaffold milestones, DI ownership, pure-JVM protocol
  module, DAG fixes). Both were accepted in full as [changes.md](changes.md).
- **G0 per chunk.** Each chunk wrote its own plan after researching current platform guidance. Safety chunks
  (alerts 04/05/11, sensor 12/18, protocol and sharing 06/07/13/15, monitor 10, setup 24, release smoke 27a) had an
  independent critic until it approved (for example, the Libre 3 sensor chunk went REVISE with 7 items, then APPROVE).
  The other chunks did a self-critique pass.
- **G2 code review per chunk.** A fresh reviewer got only the diff, the brief and a checklist (CLAUDE.md rules,
  clean V1 ids, no fake data in release, alert scenarios unchanged, error handling, concurrency, test quality). Every
  finding was fixed or answered in writing. Examples of what it caught: an alert runtime that marked itself started
  before its loads finished (a failed load would have left alerts dead), a sharing engine whose single-thread
  dispatcher was not a real mutex (an inbox commit could overwrite an approval), an expired mute still drawn as a
  "0m left" pill on the Mac, a Bluetooth failure state that never recovered.
- **UX review, batched per wave.** One reviewer looked at the merged wave's screenshots (Roborazzi goldens in light
  and dark at default and largest font; Mac snapshots; synthetic data only) against a 30-item usability and
  accessibility checklist. Batch 1 asked for fixes before release on the Android home screen (keep the card and its
  chart under a "no data" reason, safety banners that look like warnings, honest follower status) and contrast and
  VoiceOver fixes on both card renderers; these became fix chunks (`fx-home`, `fx-mac`).
- **Release smoke (27a).** The signed release APK was built with the new release key and checked by
  `tools/release/verify-apk.sh` (13 of 13: signer, package, versionCode 90000, not debuggable, native Libre 3 core
  present, no debug components), then installed next to the showcase on an emulator: both run, no R8 / JNA / native
  errors.
- **Cross-platform contracts.** Both apps run the same alert timeline scenarios (byte-identical to the PoC's), card
  layout fixtures, settings schema, protocol sync fixtures (including a 0.11.53 showcase wearer → V1 follower wire
  compatibility test) and CSV export cases from [`contracts/`](../../contracts/).
- **Final gates on `next`** (planned in plan.md §7): whole-app reviews of code, security and privacy, UX,
  accessibility and performance / battery, a feature pass over FEATURES.md and the release decision. Their results
  go in the 0.90.0 release notes and [CHANGELOG.md](../../CHANGELOG.md).
