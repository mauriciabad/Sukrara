# Project rules (cgm-reading-mac-spike)

Rules every session follows in this repo. The detailed know-how (phone, Mac, signing, orchestration) is in
[`spikes/showcase-android/NEXT.md` §5](spikes/showcase-android/NEXT.md).

- **Investigations are standalone PoCs** with a report in `docs/NN-*.md`. Bringing their results into the Sukrara
  showcase app is a separate step.
- **Document what goes in and what stays out.** When results from an investigation are brought into Sukrara, record
  both **what was added to the app** and **what was discarded, with the reason**. Put it in:
  - the investigation's report ("In Sukrara" section)
  - `docs/10-showcase.md`
- **The README always stays up to date** with what Sukrara (Android and Mac) does. Update it in the same change as the
  feature.
- **No backwards compatibility.** PoCs are throwaway: replace formats outright and delete superseded code paths.
- **Android: Kotlin only** (JNI/C is fine). Use the newest API generation. Put a GPL header on every file.
- **Releases:** a bug fix gets a GitHub release only after the user has confirmed the fix on their device.
- **Alert rules are guarded by timeline scenarios** (sequences of events → expected alerts, Android and Mac). Every
  alert-rule change adds or updates a scenario; never change a scenario's expectations without the user's OK.
- **`docs/FEATURES.md` is the behaviour catalog** for the final app: every behaviour change updates it in the same commit.
- **One graph everywhere.** Every change to the glucose graph / value card (marks, badge, slots, units, colours) lands on
  Android AND Mac (`spikes/widgets-macos/Shared/SukraraGraph.swift`: menubar dropdown + Mac widgets) in the same round.
