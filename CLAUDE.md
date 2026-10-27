# Project rules (Sukrara)

Rules every session follows in this repo. The detailed know-how (phone, Mac, signing, orchestration) is in
[`poc/spikes/showcase-android/NEXT.md` §5](poc/spikes/showcase-android/NEXT.md).

**Run `tools/check/run.sh` before committing and before merging** (`--help` lists the checks). The git hooks run it
automatically once `tools/hooks/install.sh` has been run in the clone (worktrees share it): `pre-commit` fails on the
static rules, `commit-msg` reminds about parity / FEATURES.md on a normal commit and **fails a merge** that brings
one-sided changes. CI (`.github/workflows/checks.yml`) runs the same checks plus the Android unit tests and the Mac
self-tests. A check that is wrong for your change is skipped with a commit-message trailer that says why
(`Parity-skip: <group>: <reason>`, `Features-skip: <reason>`), never with `--no-verify`. The manual checks per
surface are in [`docs/QA.md`](docs/QA.md); `run.sh` prints the sections your change touches.

- **Investigations are standalone PoCs** with a report in `docs/NN-*.md`. Bringing their results into the Sukrara
  showcase app is a separate step. *(not checked)*
- **Document what goes in and what stays out.** When results from an investigation are brought into Sukrara, record
  both **what was added to the app** and **what was discarded, with the reason**. Put it in:
  - the investigation's report ("In Sukrara" section)
  - `docs/10-showcase.md` *(not checked: needs judgement)*
- **The README always stays up to date** with what Sukrara (Android and Mac) does. Update it in the same change as the
  feature. *(check `features`: a NOTE when app code changes without README.md)*
- **No backwards compatibility.** PoCs are throwaway: replace formats outright and delete superseded code paths.
  *(not checked)*
- **Android: Kotlin only** (JNI/C is fine). Use the newest API generation. Put a GPL header on every file.
  *(checks `java`, `header`: new source files)*
- **Strings:** every user-visible Android text in `values/strings.xml` and `values-es/strings.xml` (NEXT.md §5).
  *(check `strings`: new hardcoded `Text("…")` etc., en/es keys; `StringsParityTest`)*
- **Never commit secrets** (`nsec1…`, `secrets/`, keys, keystores, APKs/dmgs, build dirs) **or the user's health
  screenshots** (synthetic/emulator data, cropped). *(checks `secrets`, `large`, `images`: images only under
  `docs/img/` or `poc/spikes/*/evidence/`, full-phone captures flagged)*
- **Phone / Mac installs contain all merged work:** install only with `tools/install.sh phone|mac` (refuses a dirty
  tree, a HEAD without origin/main / the integration branches / the last installed commit, or failing checks).
- **Cleartext only where it's validated:** the app permits cleartext so a home-network Nightscout works; every
  `http://` / `ws://` URL or scheme and every place that opens a connection in the Android app needs an entry in
  `tools/check/allow.json` (`cleartext_ok` / `network_ok`) with the reason. *(check `cleartext`, whole tree; `NsSiteTest`)*
- **Test and snapshot runs never touch the user's devices:** every Mac `-…Snapshot` / preview run and every dev build
  runs offline — no Bluetooth, relays, LAN feed or alert engine (`LaunchGuard.services`, the one gate in main.swift).
  *(`-argsselftest` in CI: the plan per flag and main.swift starting nothing outside it)*
- **Debug commands never fake on a release build:** the release app never shows a glucose value, an alert, a treatment
  or a sensor state that isn't real (the user acts on it). DebugReceiver commands that replace the sensor or show /
  write made-up data (test alerts, status-bar previews, adb treatments, …) go through `DebugFakes.run` (debug builds
  only; test them on the emulator); reading state changes nothing. *(check `debug`; `DebugFakesTest`)*
- **Releases:** a bug fix gets a GitHub release only after the user has confirmed the fix on their device.
  *(not checked)*
- **Alert rules are guarded by timeline scenarios** (sequences of events → expected alerts, Android and Mac). Every
  alert-rule change adds or updates a scenario; never change a scenario's expectations without the user's OK.
  *(parity groups `alerts`, `alert-scenarios`, `alert-spec`; `AlertScenarioTest` + `-alerts-selftest` in CI)*
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
- **`docs/FEATURES.md` is the behaviour catalog** for the final app: every behaviour change updates it in the same commit.
  *(check `features`)*
- **One graph everywhere.** The card (value, arrow, badges, slots, chart and every element in them) is laid out and
  drawn only by the renderer: Android `GlucoseCard` (graph/), Mac `GlucoseCard` (`poc/spikes/widgets-macos/Shared/SukraraGraph.swift`).
  Every place that shows the card or its value is registered in `poc/spikes/graph-fixtures/surfaces/surfaces.json` (and the
  `CardSurface` enums); a card surface only chooses its size, its place's settings and the `CardPart`, a text / icon
  place takes its value from `GlucoseCard.face`. A change to the card lands on Android AND Mac in the same round; a new
  surface is registered and rendered by the cross-surface tests. *(checks `card-surface`, `card-api`, `card-text`;
  `CardLayoutTest`, `FreshnessFixtureTest`, `GraphFixtureTest` and `-graphselftest` over `poc/spikes/graph-fixtures`, incl.
  `layout/rules.json` + `expect.json`; `tools/check/run.sh --layout` runs them locally; parity group `graph`)*
- **Settings never diverge across platforms.** Every setting of both apps is in THE schema
  `poc/spikes/settings/settings.json` (store + key per platform, or the one platform with `only` + reason; its number
  format; whether followers mirror it from the wearer). Number settings use the one number input (Android
  `NumberDialog`, Mac `NumberSettingRow`) with the generated `SettingsSpec` (`python3 tools/settings/gen.py` after a
  schema change). *(check `settings`; `SettingsSchemaTest` + `-settingsselftest` in CI)*
- **Protocols** (`poc/spikes/nostr/PROTOCOL-*.md`, `poc/spikes/alerts/SPEC.md`): a spec change lands with both
  implementations. *(parity groups `protocol-v2`, `identity`, `alert-spec`, `treatments`)*
