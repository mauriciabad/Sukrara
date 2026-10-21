# Sukrara manual QA

What automated checks can't see: how each surface looks and behaves. `tools/check/run.sh` prints the sections a change
touches (map: `tools/check/qa.json`); go through them before saying a change works, and attach the before/after
pictures to the report. Cheapest way first in every section.

**Data rules for every picture:** synthetic or emulator data only. Never commit the user's real glucose, crop phone
screenshots so no other apps' notifications show, and keep phone checks read-only (NEXT.md §5).

Automated already (don't repeat by hand): `tools/check/run.sh` (repo rules), Android unit tests
(`./gradlew :app:testDebugUnitTest`: alert scenarios, graph fixtures, strings parity, stats), Mac self-tests
(`.build/debug/GlucoseBar -alerts-selftest`, `-graphselftest`, `-exportselftest`). CI runs all of them (`.github/workflows/checks.yml`).

Cheap tools:

- **JVM gallery** (no device): `cd spikes/showcase-android && ./gradlew :app:testDebugUnitTest --tests '*GraphGallery*'
  -Dgallery.out=/tmp/gallery` renders the real card/graph/rings code to PNGs (light + dark, several sizes).
- **Mac renderer** (no widget host): `spikes/widgets-macos/build.sh render` (every widget family → evidence/) and
  `render treatments <dir>` (marks, badge, slot sheets); `defaults write app.mauri.sukrara.poc menuPreview <png>` +
  launching a test build renders the dropdown.
- **Emulator**: `medium_phone` (wearer, debug build = synthetic glucose) and `small_phone` (follower); at most two at
  once. DebugReceiver: `adb shell am broadcast -n app.mauri.sukrara.poc/app.mauri.sukrara.features.DebugReceiver
  --es alertFeed '204,-0.5'` (a made-up reading through the alert rules), `--es alertTest KIND` (a test alert),
  `--ei previewSgv N` (a status-bar preview), `--es cmd treat …`, `cmd locktest`, `cmd newSensor --es stage …`, see
  docs/10. **These fake commands run only on the emulator's debug build**: the release app never shows a glucose
  value, an alert, a treatment or a sensor state that isn't real (a release build refuses them, `debugOnly` in the reply).
- **Phone** (`62240DLCH0005W`): only after `tools/install.sh phone`; read-only adb, `adb exec-out screencap -p`
  while unlocked. No fake commands (they're refused on release); root/LSPosed checks that need a value use the real one.

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
- [ ] Scrolling (app panel, Mac dropdown): `screenrecord` a slow drag, a fling, a fling against now (stretch) and a tap
      on *Now*; step the frames: the % column, distribution, y range (eased, no jump), hour labels and treatment marks
      follow the window; the plot doesn't jump when a treatment scrolls in or out; the pill fades in and out. Mac: the
      same with two-finger swipes (momentum after lifting) and a mouse wheel; `-panelPreview` writes `*-scrolled.png`.
- [ ] Sensor life (JVM gallery `sensorLife`, Mac `render treatments` → `mac-sensor-*.png`; emulator: `--es cmd sensor
  --el left <s>`): badge `13d` / `20h` (no ring with an 8 h window) / `5h` with the countdown ring over the main ring,
  legible over every band colour, light and dark; warm-up `42m` + filling ring; ended `Sensor ended` + `Replace sensor` pill on every widget size (3×1 and 2×1 too, also while the last value is fresh; pill/tap opens the Sensor screen on the wearer's phone), checkered flag in the status bar (notification icon, root slots, hook) and the Mac menubar + no-data alert
  quiet. Follower and Mac show the same from the live events. Evidence 2026-10-18:
  `spikes/showcase-android/evidence/sensor-life-emulator-{13d,20h,5h}.png` (emulator app panel),
  `sensor-life-gallery-*.png`, `spikes/widgets-macos/evidence/mac-sensor-*.png`.

## Q3 Android widgets

- [ ] Emulator: add value, graph and combined widgets (2x1, 4x2, larger); light and dark wallpapers.
- [ ] They update on a new reading (synthetic source) within a minute and after `adb shell am force-stop` + reopen.
- [ ] Release build (R8) once per round: widgets still render (proguard rules).
- [ ] Share-with-your-clinic banner (emulator, wearer, nothing uploading): home and Settings show *Share your data with
  your clinic* with *LibreView* and *Tidepool* pills (real icons); each opens its page; setting either up hides it on
  both; close hides it for good. Spanish wording fits.
- [ ] Add-the-widget banner (emulator, Pixel Launcher): with no graph widget, home shows the card under the glucose
  panel; *Add widget* opens the launcher's dialog with the preview; *Add to home screen* places it and the card is gone
  on return; remove the widget → the card is back; close → gone after a force-stop too (`--es cmd widgetBanner --ez
  reset true` brings it back). Spanish wording fits.

## Q4 Lock screen widget

- [ ] Needs root/LSPosed: the phone only, after install. Check light and dark lock screens, frosted glass on/off,
      appearance Light / Dark / Automatic.
- [ ] Treatment buttons work without unlocking; the value updates while locked.
- [ ] No other app's notifications in the screenshots.

## Q5 Glucose notification

- [ ] Emulator: pull down the shade; collapsed (value + slots) and expanded (card) in light and dark.
- [ ] Lock screen version (public/redacted) shows what the settings say.
- [ ] Live Update chip / status bar value if the change touched them (phone).

## Q6 Mac menubar panel

- [ ] Test build at another path (never over `~/Applications/Sukrara.app`): menubar number + colour; click → panel
      (card in its module, Return opens *Add Treatment*: Tab rapid → long → carbs → note, `4.5u` keeps `4.5`,
      quiet line, limits line at 600g, time chip popover, Return saves all, Escape cancels; Escape / click outside
      closes the panel); right-click menu; Treatments… (7 days, delete with ⌫ / context menu, ⌘W); Glucose Report (report only, period, Export CSV…); Settings (each pane; ⌘, ⌘W, paste in
      the invite field); light and dark, over a bright and a dark window/wallpaper, Increase Contrast and Reduce
      Transparency (System Settings → Accessibility → Display).
- [ ] Real captures (Screen Recording for the terminal): launch the build with `-openPanel [-appearance dark]`, take
      `screencapture -x -R<rect>` with the rect it logs ("panel window … rect x,y,w,h"); never commit them (real data).
- [ ] Synthetic renders for docs: `GlucoseBar -panelPreview <dir>` (the real panel over test wallpapers), and
      `-settingsSnapshot <dir>` / `-reportSnapshot <dir>` (with `SUKRARA_DEV=1` in the environment and
      `-keychainService <test> -readingsDir <dir> -treatmentsDir <dir> -v2NoRelays YES -bleV2 NO`; a dev build refuses
      to start the full app without `SUKRARA_DEV=1`, LaunchGuard).
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

## Q11 Sensor reminders and countdowns

- [ ] Emulator (wearer): `--es cmd sensorEnd --ei inMin 300` → the end countdown in the shade (bar, timer, Hide);
      with the glucose chip off (`--ez chip false`) the status bar chip shows the timer. `--es reminders '0@<now+1 min>'`
      → a reminder a minute later ("Sensor ends today at …"). `--ei inMin 1` → "Sensor ended" a minute later.
- [ ] `--es cmd sensorStart --ei agoMin 40` → the warm-up countdown (bar filling, chip timer); `--es cmd sensorStart
      --ez firstReading true` → "Sensor ready".
- [ ] Swipe the countdown away: it doesn't come back for that sensor (`--es cmd sensorEndState` → `dismissed`).
- [ ] Settings → Sensor end: add / edit / remove a reminder (dialog: days radio, time picker), countdown hours,
      both switches; the card's countdown ring follows the hours. Spanish texts.
- [ ] Follower role: none of these notifications.

## Q12 Sensor scan

- [ ] Unit tests first: `Libre3NfcTest` (commands, answers, receiver ID, `chipGate`), `PatchStateTest`.
- [ ] Unit tests: `NewSensorFlowTest` (steps after the tap, home button). Emulator: `--es cmd newSensor --es stage
      <each stage>` with the screen open (`am start … --es screen newsensor`): light and dark, English and Spanish;
      home's button filled after `fakeSensor` with an end in the past, tonal with an end < 24 h away, gone otherwise.
- [ ] Settings → Glucose source (one page): header with the sensor (family, red *Untested* tag when unverified, serial,
      dates, status) and *Pair new sensor*; *Read glucose from*: *This phone* (named family) / *Juggluco* (from
      Juggluco to *This phone* asks first, then the New sensor screen); *Sensor*: open on scan, *Forget this sensor*.
      Settings search finds *Pair new sensor*, *This phone*, *Juggluco*, *Forget this sensor*.
- [ ] Phone, the user does the tap, home → *Pair new sensor* (or Settings → Glucose source → *Pair new
      sensor*): the circle sits on the phone's real NFC spot; the tap vibrates, then *Pairing* ticks its steps and
      ends in the warm-up countdown (new) or *Sensor ready* (taken over). With Sukrara closed, a tap opens the New
      sensor screen and uses that tap. Libre 3 / 3 Plus: starts or takes over;
      `adb logcat -s Sukrara.nfc Sukrara.direct` shows the patch info and the pairing. A Libre 1 / 2 / 2 Plus (or any
      other NfcV tag) shows *Libre 2 sensors aren't supported* / *Not supported*, and logcat shows
      `Libre 1/2 chip: not supported, nothing sent` with no command lines.
- [ ] A new Libre 3 without a known LibreView receiver ID: *Sign in to LibreView*, nothing sent; after the
      sign-in the next tap starts it (logcat `patch info … state=1`, then the pairing).
- [ ] **Libre 3 unchanged by the brand step** (investigation 23): the screen opens on *Which sensor are you
      starting?* with *FreeStyle Libre 3 / 3 Plus* selected; tapping the sensor right there starts the scan (no
      *Continue* needed); *Continue* → the same NFC flow as before; no *Untested* tag, no confirmation, no banner on
      home. After the install, `adb logcat -s Sukrara.direct Sukrara.scan` shows the usual Libre 3 connection by
      address and **no `Sukrara.scan` line at all**; readings keep arriving every minute.
- [ ] Untested families (emulator only; nobody has the sensors): `--es stage choose|untested|pairfailed|setup-dexcom-g6|
      setup-aidex-x|setup-accu-chek-smartguide|setup-dexcom-g7`: every brand but the Libre 3 has the *Untested* tag;
      *Untested* tags are red with the flask; *Continue* with an untested family opens the red confirmation, counting
      *Continue anyway (10)* … *(1)* with the button disabled, then *Continue anyway* → the family's setup; *Cancel*,
      back and a tap outside close it and stay on the brand step; reopening restarts the count; the Libre 3 never
      asks. *Pair* is disabled until a typed id is valid. Light and
      dark, English and Spanish. Unit tests: `BrandStepTest`, the driver tests (`G7DriverTest`, `G6DriverTest`,
      `SmartGuideDriverTest`, `AidexXDriverTest`, `CrcTest`), `DirectSourceTest` (the Libre 3 never scans).
- [ ] Untested banner: `--es cmd sensorProfile --es profile dexcom-g7` (debug) → home shows *Dexcom G7 / ONE+
      support is untested. Tell us how it works.* directly under the graph, no close button, not red; *Send feedback*
      opens github.com/…/issues/new with the sensor-feedback form pre-filled with the sensor, the Sukrara and Android
      versions and the phone model only (`SensorFeedbackTest`); `--es profile libre3` → gone; a follower never shows it.
- [ ] Untested family, outputs: with `sensorProfile dexcom-g7`, Settings → Share with apps → *Apps on this phone*
      starts with *Paused: Dexcom G7 / ONE+ support is untested* and `adb logcat -s Sukrara.broadcast` stays silent for
      readings tagged with an untested family; `libre3` → the line is gone and the allowed apps get readings again.
      Unit tests: `BroadcastPayloadsTest` (no broadcast without a verified label), `SensorOutputsTest` (Health
      Connect, Tidepool, Nightscout, phone APIs, CSV), `LibreViewUploaderTest`.

## Q13 Sensor end in calendar

- [ ] Emulator (wearer, debug): `--es cmd fakeSensor` (synthetic TEST serial), then Settings → Sensor end → "Sensor end in calendar" on: the
      permission dialog; Allow → "On · Sukrara (this phone only)"; Don't allow → stays off with the reason.
- [ ] `--es cmd calendar` dumps one "Sensor ends" event at the sensor's end; a second `fakeSensor` with a new serial
      moves the future event to the new sensor (the old future one is removed); restart the app → still one event per sensor.
- [ ] A calendar app on the device shows the "Sukrara" calendar in the brand colour (if the image has one).
- [ ] Off → the Sukrara calendar is gone (`content query --uri content://com.android.calendar/calendars`).
- [ ] Picking a Google calendar (needs a signed-in account): the event appears on another device after sync and goes
      away again when the setting is turned off.

## Q14 Apps on this phone and Health Connect

- [ ] Emulator (wearer, debug = synthetic glucose) with GlucoDataHandler installed: Settings → Share with apps lists
      it under *Apps on this phone* with its own icon, switch **off**; `adb logcat -s Sukrara.broadcast` stays silent.
- [ ] Switch on → each minute `→ de.michelinside.glucodatahandler 3`; GDH (`logcat | grep GDH`) logs
      `XDripBroadcastReceiver … SourceInfo => Libre3` and `Glucodata received from XDRIP`, and shows the value.
- [ ] `--es cmd fakeSensor --es serial TEST…`: the next minute sends 4 (SENSOR_ACTIVATE), GDH `setSensorStartTime`.
- [ ] Source → Juggluco: the app switches are greyed with "Only while Sukrara reads the sensor itself"; nothing sent.
- [ ] Follower role: the page has neither group.
- [ ] Health Connect on → Health Connect's own sheet; Don't allow → stays off with "needs access in Health Connect";
      Allow → `Sukrara.health backfill N`, Health Connect → Blood glucose lists Sukrara entries, one per minute.
- [ ] Off → On again: the `wrote …` ids repeat the first run's ids; still one entry per minute in Health Connect.
- [ ] Spanish: the two groups and summaries are translated.
- [ ] Icons: open Settings → Apps → All apps beside Share with apps. GlucoDataHandler (adaptive), xDrip+ (legacy) and
      Health Connect have the same size, shape and shadow in both; LibreView, Nightscout (switch and upload row) and
      Juggluco (Glucose source, with Juggluco not installed) show their logos at that size; Tidepool shows the
      cloud symbol. Same in dark theme.
- [ ] Garmin Connect (or a stub app with package `com.garmin.android.apps.connectmobile`) installed: it appears under
      *Apps on this phone* with "Reads your glucose through the xDrip+ server"; its switch turns the xDrip+ server
      on/off, and the server row says "read by Garmin Connect". Without any receiver or Garmin app the group says
      "… Garmin watches read the xDrip+ server below."

## Q15 Data export

- [ ] Emulator (wearer, debug = synthetic glucose): Settings → Export data; the count updates when the period changes;
      This sensor shows only when a sensor is known (`--es cmd fakeSensor`).
- [ ] *Save file*: the system picker suggests `sukrara-glucose-<from>-to-<to>.csv`; saving shows "File saved";
      `adb shell cat` the file (or open it in Files): line 1 `Glucose Data,…`, 5-minute rows, treatments with
      `Entered By`.
- [ ] *Share*: the share sheet shows the CSV (a preview line with the file name, no person's name); sending it to an
      app (Drive, Gmail, Files) attaches the file.
- [ ] Follower (`small_phone`): the same page reads "already on this phone" and exports what it received.
- [ ] Mac: Glucose Report › Report › *Export CSV…*: the save panel suggests the same name for the report's period; the
      file opens in Numbers.
- [ ] Import check (optional): `spikes/export-fixtures/tidepool-check` runs Tidepool Uploader's LibreView driver on a
      file and prints the records it would upload.

## Q16 Tidepool upload

Use a test Tidepool account (or a real one only with synthetic emulator data you delete afterwards: Tidepool web →
the data set's device → delete, or the end-to-end test below). Never switch it on on the user's phone for a test.

- [ ] No device first: `TIDEPOOL_E2E_EMAIL=… TIDEPOOL_E2E_PASSWORD_FILE=<gitignored file> TIDEPOOL_E2E_LEDGER=<gitignored
      file> ./gradlew :app:testDebugUnitTest --tests '*TidepoolLiveTest*'` passes: it uploads a synthetic night across
      a DST change into its own data set, checks dedupe / edit / delete / notes, deletes everything and checks that
      nothing is left (`[tidepool-e2e] left: data=0 … e2e data sets=0 notes=0` in the test's output). Each run leaves one
      empty deleted upload record (Tidepool's tombstone; the API can't remove it), so run it only when needed.
- [ ] Emulator (wearer, debug = synthetic glucose): Settings → Share with apps → Tidepool: account *Not signed in*,
      main switch greyed. Wrong password → "Tidepool didn't accept this email and password", dialog stays.
- [ ] Sign in → the account row shows the email; nothing uploads until *Upload to Tidepool* is on. On → `adb logcat -s
      Sukrara.tidepool` shows `uploaded N readings …`; status "Up to date · last upload …"; Tidepool web shows the
      readings (one per 5 minutes) under a *Sukrara* device.
- [ ] `--es cmd treat …` carbs and rapid insulin, then edit and delete one in the app: Tidepool web follows within a
      cycle (one food / insulin each, the deleted one gone); a note on its own shows as a Tidepool note.
- [ ] Airplane mode for 15 min → status "Will retry · …"; back online → the gap is filled (no missing 5-minute slots).
- [ ] Follower (`small_phone`): the page has *Upload the person you follow*, off; status "Waiting for your choice
      below" and no upload in the log until it's on.
- [ ] Sign out → account *Not signed in*, main switch off; the data stays on Tidepool.
- [ ] Spanish: every row, summary, dialog and status is translated.

## Q17 Nightscout upload

Only a local, throwaway Nightscout with synthetic data (`spikes/nightscout-upload/verify/nightscout.sh up`; `down`
afterwards). Never a hosted site, never the user's phone.

- [ ] No device first: with the site up, `NS_E2E_URL=http://localhost:1337 NS_E2E_SECRET=… NS_E2E_TOKEN=…
      NS_E2E_READ_TOKEN=… ./gradlew :app:testDebugUnitTest --tests '*NightscoutLiveTest*'` passes (30 days in, a late
      gap filled, 0 gaps / 0 duplicates, treatments, both sensor events, battery, v3 edit + move + delete), then
      `spikes/nightscout-upload` `cli check` (24/24) and `followers/run.sh` pass against the same site.
- [ ] Emulator (wearer, debug = synthetic glucose; `adb reverse tcp:1337 tcp:1337`): Settings → Share with apps →
      Nightscout → *Nightscout upload*: *Not set up*, main switch greyed. A follower has no such row.
- [ ] Site dialog: `http://my.ns.example` → "Use https:// …"; `http://192.168.1.20:1337` → allowed with the
      "Not encrypted" warning (also under the site row once saved); `http://localhost:1337` + the access token → *Test*
      shows "Connected · Nightscout 15.0.8"; a wrong token → "The site refused the token or secret". *Save*.
- [ ] Main switch on → `adb logcat -s Sukrara.nightscout` shows `sent N`; status "Up to date · last upload …"; the site
      shows the last 30 days, the treatments, SAGE and the phone battery. A new reading is on the site within seconds.
- [ ] Add carbs, edit the amount, move it, delete it: the site follows (1 → 1 → 0 documents). Long-acting 14u: the
      site's IOB doesn't change.
- [ ] Stop the container for a few minutes → "Will retry · …"; start it → the gap fills, no duplicates.
- [ ] Post an entry from another device (see `verify/roles-and-conflict.sh`) → status "Paused: another app (…) is
      uploading to this site" with *Upload anyway*; tap it → uploads resume.
- [ ] Wrong token saved → "The site refused the token or secret: change it", nothing more is sent until it is changed.
- [ ] `adb logcat` while uploading: no token, secret or JWT in clear. Spanish: every row, dialog and status translated.
