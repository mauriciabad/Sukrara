# Project rules (cgm-reading-mac-spike)

Rules every session follows in this repo. The detailed know-how (phone, Mac, signing, orchestration) is in
[`spikes/showcase-android/NEXT.md` §5](spikes/showcase-android/NEXT.md).

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
  `docs/img/` or `spikes/*/evidence/`, full-phone captures flagged)*
- **Phone / Mac installs contain all merged work:** install only with `tools/install.sh phone|mac` (refuses a dirty
  tree, a HEAD without origin/main / the integration branches / the last installed commit, or failing checks).
- **Cleartext only where it's validated:** the app permits cleartext so a home-network Nightscout works; every
  `http://` / `ws://` URL or scheme and every place that opens a connection in the Android app needs an entry in
  `tools/check/allow.json` (`cleartext_ok` / `network_ok`) with the reason. *(check `cleartext`, whole tree; `NsSiteTest`)*
- **Debug commands never fake on a release build:** the release app never shows a glucose value, an alert, a treatment
  or a sensor state that isn't real (the user acts on it). DebugReceiver commands that replace the sensor or show /
  write made-up data (test alerts, status-bar previews, adb treatments, …) go through `DebugFakes.run` (debug builds
  only; test them on the emulator); reading state changes nothing. *(check `debug`; `DebugFakesTest`)*
- **Releases:** a bug fix gets a GitHub release only after the user has confirmed the fix on their device.
  *(not checked)*
- **Alert rules are guarded by timeline scenarios** (sequences of events → expected alerts, Android and Mac). Every
  alert-rule change adds or updates a scenario; never change a scenario's expectations without the user's OK.
  *(parity groups `alerts`, `alert-scenarios`, `alert-spec`; `AlertScenarioTest` + `-alerts-selftest` in CI)*
- **`docs/FEATURES.md` is the behaviour catalog** for the final app: every behaviour change updates it in the same commit.
  *(check `features`)*
- **One graph everywhere.** The card (value, arrow, badges, slots, chart and every element in them) is laid out and
  drawn only by the renderer: Android `GlucoseCard` (graph/), Mac `GlucoseCard` (`spikes/widgets-macos/Shared/SukraraGraph.swift`).
  Every place that shows the card or its value is registered in `spikes/graph-fixtures/surfaces/surfaces.json` (and the
  `CardSurface` enums); a card surface only chooses its size, its place's settings and the `CardPart`, a text / icon
  place takes its value from `GlucoseCard.face`. A change to the card lands on Android AND Mac in the same round; a new
  surface is registered and rendered by the cross-surface tests. *(checks `card-surface`, `card-api`, `card-text`;
  `CardLayoutTest`, `FreshnessFixtureTest`, `GraphFixtureTest` and `-graphselftest` over `spikes/graph-fixtures`, incl.
  `layout/rules.json` + `expect.json`; `tools/check/run.sh --layout` runs them locally; parity group `graph`)*
- **Protocols** (`spikes/nostr/PROTOCOL-*.md`, `spikes/alerts/SPEC.md`): a spec change lands with both
  implementations. *(parity groups `protocol-v2`, `identity`, `alert-spec`, `treatments`)*
