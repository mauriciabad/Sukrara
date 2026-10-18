# Sukrara manual QA

What automated checks can't see: how each surface looks and behaves. `tools/check/run.sh` prints the sections a change
touches (map: `tools/check/qa.json`); go through them before saying a change works, and attach the before/after
pictures to the report. Cheapest way first in every section.

**Data rules for every picture:** synthetic or emulator data only. Never commit the user's real glucose, crop phone
screenshots so no other apps' notifications show, and keep phone checks read-only (NEXT.md §5).

Automated already (don't repeat by hand): `tools/check/run.sh` (repo rules), Android unit tests
(`./gradlew :app:testDebugUnitTest`: alert scenarios, graph fixtures, strings parity, stats), Mac self-tests
(`.build/debug/GlucoseBar -alerts-selftest`, `-graphselftest`). CI runs all of them (`.github/workflows/checks.yml`).

Cheap tools:

- **JVM gallery** (no device): `cd spikes/showcase-android && ./gradlew :app:testDebugUnitTest --tests '*GraphGallery*'
  -Dgallery.out=/tmp/gallery` renders the real card/graph/rings code to PNGs (light + dark, several sizes).
- **Mac renderer** (no widget host): `spikes/widgets-macos/build.sh render` (every widget family → evidence/) and
  `render treatments <dir>` (marks, badge, slot sheets); `defaults write app.mauri.sukrara.poc menuPreview <png>` +
  launching a test build renders the dropdown.
- **Emulator**: `medium_phone` (wearer, debug build = synthetic glucose) and `small_phone` (follower); at most two at
  once. DebugReceiver: `adb shell am broadcast -n app.mauri.sukrara.poc/app.mauri.sukrara.features.DebugReceiver
  --es alertFeed '204,-0.5'` (a made-up reading through the alert rules), `--es cmd treat …`, see docs/10.
- **Phone** (`62240DLCH0005W`): only after `tools/install.sh phone`; read-only adb, `adb exec-out screencap -p`
  while unlocked.

## Q1 App home and screens

- [ ] Emulator, light and dark: home shows value + arrow + card + graph; scroll; open every screen touched.
- [ ] Spanish: switch the per-app language (Settings → Apps → Sukrara → Language) and repeat for the changed screens;
      no clipped or English-only text outside the settings pages.
- [ ] Rotation / small window (`small_phone`) keeps the layout usable.
- [ ] Screen transitions (a change to `Motion.kt` / `ScreenHost`, a page's scaffold or a vendored SPA widget): set
      Animator duration scale to 5×, `screenrecord` home ↔ settings, a settings page push / pop and a predictive back
      swipe, and step the frames: no page drawn from scratch at the start or end of a move (empty chart, rows in another
      layout for one frame). `ScreenHostTest` (run with `GALLERY_JAVA_HOME`) checks a page isn't composed twice.

## Q2 Graph and value card

- [ ] JVM gallery before/after: marks, mute lines, badge, slots, bands; light and dark; small and large sizes.
- [ ] Mac `render treatments` before/after shows the same decisions (same labels, same badge text, same slot order).
- [ ] Units mg/dL and mmol/L both read right (setting in the app).

## Q3 Android widgets

- [ ] Emulator: add value, graph and combined widgets (2x1, 4x2, larger); light and dark wallpapers.
- [ ] They update on a new reading (synthetic source) within a minute and after `adb shell am force-stop` + reopen.
- [ ] Release build (R8) once per round: widgets still render (proguard rules).

## Q4 Lock screen widget

- [ ] Needs root/LSPosed: the phone only, after install. Check light and dark lock screens, frosted glass on/off,
      appearance Light / Dark / Automatic.
- [ ] Treatment buttons work without unlocking; the value updates while locked.
- [ ] No other app's notifications in the screenshots.

## Q5 Glucose notification

- [ ] Emulator: pull down the shade; collapsed (value + slots) and expanded (card) in light and dark.
- [ ] Lock screen version (public/redacted) shows what the settings say.
- [ ] Live Update chip / status bar value if the change touched them (phone).

## Q6 Mac menubar dropdown

- [ ] Test build at another path (never over `~/Applications/Sukrara.app`): menubar number + colour, dropdown card
      on its platter, treatments list, Glucose Report window, Settings (each pane; ⌘, ⌘W, paste in the invite field);
      light and dark menubar, Increase Contrast and Reduce Transparency (System Settings → Accessibility → Display).
- [ ] Offscreen renders: `GlucoseBar -menuPreview out.png` (light/dark/high contrast) and
      `-settingsSnapshot <dir>` / `-reportSnapshot out.png` (with `-keychainService <test> -v2NoRelays YES -bleV2 NO`).
- [ ] Relaunch the user's copy afterwards.

## Q7 Mac widgets

- [ ] `spikes/widgets-macos/build.sh render` before/after for every family.
- [ ] Real widgets only via `tools/install.sh mac` (restarts the widget extension); they update within a minute.

## Q8 Alerts on a device

- [ ] New/changed rule: a scenario in `spikes/alerts/scenarios` covers it (the checks enforce this).
- [ ] Emulator: `--es alertFeed` a reading over and under the thresholds; notification text, channel, sound, repeat.
- [ ] Mac: no fake-reading hook yet; follow the emulator wearer (or check `-alerts-selftest` output) and compare the
      headline and timing with Android.
- [ ] Never on the user's phone with real alarms unless the user asked.

## Q9 Translations

- [ ] Every new key in `values/strings.xml` and `values-es/strings.xml` (the checks and StringsParityTest enforce
      the keys; a person checks the wording: Spain Spanish, hidratos, insulina rápida / lenta).
- [ ] Spanish UI on the emulator for each changed screen; long Spanish strings don't clip in widgets or the card.

## Q10 Release build

- [ ] `./gradlew :app:assembleRelease` and install on the emulator; open home, widgets, settings (R8 surprises).
- [ ] The version in `build.gradle.kts` / `bundle.sh` matches what the release notes say.
