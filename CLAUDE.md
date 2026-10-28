# Project rules (Sukrara)

Rules every session follows in this repo. Sukrara is a set of apps under [`apps/`](apps) (Android, Mac, …), every
one tested against the shared [`contracts/`](contracts) (specs, schemas, fixtures). The research PoC and its
investigations are **frozen** under [`poc/`](poc): buildable, never edited, and checked only for
secrets, large files and Java. The know-how (setup, devices, signing, install, strings) is in
[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md); the architecture in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

**Run `tools/check/run.sh` before committing and before merging** (`--help` lists the checks). The git hooks run it
automatically once `tools/hooks/install.sh` has been run in the clone (worktrees share it): `pre-commit` fails on the
static rules, `commit-msg` reminds about parity / FEATURES.md on a normal commit and **fails a merge** that brings
one-sided changes. CI (`.github/workflows/checks.yml`) runs the same checks plus every app's builds and tests; its
`gate` job is the only required check. A check that is wrong for your change is skipped with a commit-message trailer
that says why (`Parity-skip: <group>: <reason>`, `Features-skip: <reason>`), never with `--no-verify`. A file a
registry names that doesn't exist is a "pending file" NOTE while `tools/check/strict` is absent, and fails once it
exists. The manual checks per surface are in [`docs/QA.md`](docs/QA.md); `run.sh` prints the
sections your change touches.

- **Investigations are standalone PoCs** with a report in `docs/NN-*.md`. Bringing their results into Sukrara is a
  separate step. *(not checked)*
- **Document what goes in and what stays out.** When results from an investigation or the PoC are brought into
  Sukrara, record both **what was added** and **what was discarded, with the reason**, in the investigation's report
  ("In Sukrara" section). *(not checked: needs judgement)*
- **The README always stays up to date** with what every Sukrara app does. Update it in the same change as the
  feature. *(check `features`: a NOTE when app code changes without README.md)*
- **No backwards compatibility.** Replace formats outright and delete superseded code paths. This covers local
  storage too (settings, files, databases) and anything from the PoC. The **wire formats** in `contracts/protocol`
  (Nostr v2, identity) are versioned: every app speaks the same version. *(not checked)*
- **Android: Kotlin only** (JNI/C is fine). Use the newest API generation. Put the GPL header on every new file
  (`// Sukrara — https://github.com/mauriciabad/sukrara` / `// Copyright (C) 2026 Maurici Abad` /
  `// SPDX-License-Identifier: GPL-3.0-or-later`; `#` for py / sh / yml, `<!-- -->` for xml).
  *(checks `java`, `header`: new source files, and files moved out of `poc/`)*
- **Formatted code:** Kotlin through Spotless (ktlint + Compose rules, `apps/android/.editorconfig`; `./gradlew
  spotlessApply` fixes), Swift through `swift format` (`apps/macos/.swift-format`; `swift format --in-place
  --recursive Sources Tests`). Generated `SettingsSpec` files are left as the generator writes them.
  *(Spotless in `./gradlew check`; check `format`: changed Swift files; CI mac job; `run.sh --format` for both)*
- **Strings:** every user-visible Android text in the owning module's `res/values/strings.xml` and
  `res/values-es/strings.xml` (docs/DEVELOPMENT.md "Strings"). *(check `strings`: new hardcoded `Text("…")` etc.,
  each module's en/es keys; `StringsParityTest`)*
- **Never commit secrets** (`nsec1…`, `secrets/`, keys, keystores, APKs/dmgs, build dirs) **or the owner's health
  screenshots** (synthetic/emulator data, cropped). The Android release key lives outside the repo
  (`~/.android/sukrara-release.jks`, password in the Keychain). *(checks `secrets`, `large`, `images`: images only
  under `docs/img/`, `assets/brand/`, `res/`, the snapshot goldens or `poc/spikes/*/evidence/`; full-phone captures
  flagged)*
- **Phone / Mac installs contain all merged work:** install only with `tools/install.sh phone|mac` (refuses a dirty
  tree, a HEAD without origin/main / the integration branches / the last installed commit, or failing checks; verifies
  the signed build first).
- **Cleartext only where it's validated:** the app permits cleartext so a home-network Nightscout works; every
  `http://` / `ws://` URL or scheme and every place that opens a connection in the Android app needs an entry in
  `tools/check/allow.json` (`cleartext_ok` / `network_ok`) with the reason. *(check `cleartext`, everything outside `poc/`)*
- **Test and snapshot runs never touch the owner's devices:** every Mac snapshot / test run and every dev build runs
  offline — no Bluetooth, relays or alert engine. The Mac decides this in one pure, unit-tested place, `LaunchMode`
  (normal | snapshot (DEBUG) | test → the services it may start). Tests never use real relays or sensors.
  *(`AppTests` in CI)*
- **Debug code is compiled out of release builds:** the release app never shows a glucose value, an alert, a treatment
  or a sensor state that isn't real (the owner acts on it). Debug commands that replace the sensor or make up data
  live only in Android `src/debug/` (DebugReceiver, DebugFakes, synthetic sources) and in Mac `#if DEBUG` code or the
  `sukrara-snap` / test targets; test them on the emulator. *(check `debug`; `verify-apk.sh` / `verify-app.sh` at
  install and release)*
- **Releases:** 0.x versions are GitHub prereleases; a bug fix gets a release only after the owner has confirmed the
  fix on their device. *(not checked)*
- **Alert rules are guarded by timeline scenarios** (`contracts/alerts/scenarios`: sequences of events → expected
  alerts, run by every app). Every alert-rule change adds or updates a scenario; never change a scenario's
  expectations without the owner's OK. *(parity groups `alerts`, `alert-scenarios`, `alert-spec`;
  `AlertScenarioTest` + `AlertsTests` in CI)*
- **Complete flows.** Every feature is designed as a whole user journey: every state a user can enter has a visible
  way to see it, change it and leave it (undo, cancel, turn off, unmute, go back), on every platform and surface that
  shows it, reachable without a notification and with TalkBack / VoiceOver. List the states and exits in the
  feature's **States and exits** paragraph in `docs/FEATURES.md`. *(check `states`: every FEATURES.md section has
  one or is on `allow.json` `states_pending`; QA.md Q19 walks it by hand)*
- **Design from the person's life first.** Every feature starts from the person with diabetes outside the app: why
  they want it, the real contexts they'll use it in (meal out, night, hurry, phone locked, travel), what it can take
  off their mind, what triggers the action (can it be detected, predicted, automated or prompted at the right moment),
  how they discover it without stealing attention from glucose and alerts, the newest platform tech that helps, what
  real people say online (forums, Reddit, reviews, DIY-app issues) and what official and DIY apps do (judged: good or
  bad for the person). Then the moments before and after, every surface in both directions, and nothing that blocks
  unless a confirmation already exists or safety demands it. Feature rounds get a product / UX / QA agent that writes
  this down before UI is built and reviews the screenshots. *(not checked: needs judgement; QA.md Q20)*
- **Make it juicy.** Every interaction feels snappy, enjoyable and beautiful, like good game feel: immediate response,
  motion that explains what happened (Material motion / SwiftUI springs, shared transitions, numbers that count),
  haptics that confirm (Android `HapticFeedbackConstants` / `VibrationEffect` primitives, Mac `NSHapticFeedbackManager`
  where there's a trackpad), sound only where the platform expects it, polished graphics. Never at the cost of clarity,
  speed or accessibility: honour reduced motion, TalkBack / VoiceOver and the alert sounds' meaning.
  *(not checked: needs judgement; QA.md Q20)*
- **`docs/FEATURES.md` is the behaviour catalog** for every app: every behaviour change updates it in the same
  commit. *(check `features`)*
- **One graph everywhere.** The card (value, arrow, badges, slots, chart and every element in them) is laid out and
  drawn only by the renderer: Android `:core:graph` (`GlucoseCard`), Mac `SukraraGraph` (`GlucoseCard`). Every place
  that shows the card or its value is registered in `contracts/graph/surfaces/surfaces.json` (and the `CardSurface`
  enums); a card surface only chooses its size, its place's settings and the `CardPart`, a text / icon place takes
  its value from `GlucoseCard.face`. A change to the card lands on every app in the same round; a new surface is
  registered and rendered by the cross-surface tests. *(checks `card-surface`, `card-api`, `card-text`; every app's
  graph tests over `contracts/graph` incl. `layout/rules.json` + `expect.json`; `tools/check/run.sh --layout`; parity
  group `graph`)*
- **Settings never diverge across platforms.** Every setting of every app is in THE schema
  `contracts/settings/settings.json` (store + key per platform, or the one platform with `only` + reason; its
  number format; whether followers mirror it from the wearer). App code takes every stored key from the generated
  `SettingsSpec` (`python3 tools/settings/gen.py` after a schema change); number settings use the one number input
  (Android `NumberDialog`, Mac `NumberSettingRow`). *(check `settings`; `SettingsSchemaTest` on every app in CI)*
- **Protocols and specs** (`contracts/protocol/PROTOCOL-*.md`, `contracts/alerts/SPEC.md`, `contracts/treatments`,
  `contracts/export`): a change lands with every implementation. *(parity groups `protocol-v2`, `identity`,
  `alert-spec`, `treatments`, `export`)*
