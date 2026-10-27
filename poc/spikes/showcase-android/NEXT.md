# Sukrara showcase — what's left (handoff for the next orchestrator)

*Updated 2026-10-16 ~15:00 after polish round 2 (docs/10 "Polish round 2").* The showcase is **Sukrara for Android**
(`poc/spikes/showcase-android`, `app.mauri.sukrara.poc`) and **Sukrara for Mac** (`poc/spikes/menubar`, `Sukrara.app`, same
id). Read `docs/10-showcase.md` first (features, defaults, DebugReceiver table, gap audit) and `README.md`.

## 1. Done in polish round 2 (for reference)

All five user requests of the previous NEXT.md, plus three new ones, are built, merged and on the phone + Mac:
follower writer gating with sent/accepted status (Android + Mac), treatments for followers (Nostr + BLE, Android +
Mac, follower Nightscout API), treatments drawn in every chart, the Live Update chip as value + arrow, the expanded
card (MetricStyle with the chip, custom graph card without), the chart under the value on the home screen, hiding
Sukrara's notification icon (root/LSPosed), Mac one-Bluetooth-link fix, thin-history fix, Home Assistant re-test,
Garmin state label. The Juggluco-port fix (§4 below) ships with this round.

## 2. Open

*Pruned 2026-10-19 (0.11.26).*

### Next (decided by the user)

- **Move Sukrara to OGS draft 3** — the user starts within days (decided 2026-10-19). Plan and cost:
  [docs/24b "Cost of moving Sukrara"](../../../docs/24b-open-standard-draft3.md#cost-of-moving-sukrara) (~30 agent-days:
  Android ~19, Mac ~11.5; every follower must be re-invited; needs secp256k1 tweak-add on both platforms). Reviewed
  spec: [`poc/spec/draft3`](../../spec/draft3), [docs/24c](../../../docs/24c-draft3-review.md). Published at
  [github.com/mauriciabad/open-glucose-stream](https://github.com/mauriciabad/open-glucose-stream) (CC0, WIP; on
  Sukrara 1.0: keep only the latest spec, delete old drafts, reset numbering).
- **In-app AGP report: a new investigation.** No PNG/PDF exports as the experience; reuse
  [docs/26](../../../docs/26-agp-report.md)'s engine and fixtures.
- **More sensor brands ([docs/23](../../../docs/23-sensors.md) steps 2–6): on hold** until the user has hardware (only a
  Libre 3 Plus; shopping list in docs/23 "Hardware to buy"). Step 1 (`SensorDriver`) shipped in 0.11.23.
- **Tidepool proper sign-in**: needs an OAuth client id from Tidepool ([docs/27](../../../docs/27-tidepool.md)). The
  logo needs Tidepool's written permission ([docs/10 "Service and app icons"](../../../docs/10-showcase.md#service-and-app-icons-2026-10-19)).
  Five empty test upload records on the user's Tidepool account: only Tidepool support can purge them.
- **Upstream drafts the user sends themselves**: the Juggluco G7 J-PAKE report
  ([`poc/spikes/sensors-android/upstream/juggluco-g7-jpake-issue.md`](../sensors-android/upstream/juggluco-g7-jpake-issue.md),
  private email first) and the OGS outreach texts ([`poc/spec/drafts/outreach.md`](../../spec/drafts/outreach.md)). No NIP PR.
- **Not yet verified on devices**: export page in the follower role; Bluetooth security fixes over the air between
  two devices (0.11.22); Home Assistant against the uploaded Nightscout; the Mac *Export CSV* button on screen.
- **Battery**: re-run investigation 11's harness (`poc/spikes/power/`) with the new uploaders/broadcasts on, and again
  when a scanning sensor driver lands. The plan (`poc/spikes/power/plans/sukrara.txt`) doesn't toggle `hook` /
  `hideNotifIcon` yet: add a window for them.

### Still open

- **Thin history fix not seen end to end** with a fresh follower (two emulators: wearer `medium_phone`
  debug/synthetic, follower `small_phone`; steps in docs/10 Known issues).
- **Follower-side gaps**: no re-send of un-accepted writer entries when BLE reconnects (relay copies still get
  picked up); after the wearer is reinstalled while a follower is connected over BLE, the follower keeps a stale
  "connected" link until its app restarts.
- **Follower role doesn't feed the root status bar** (system icons / LSPosed value / hide-icon switch).
- **Mac → phone treatment over v2 on the real devices** not clicked through (the e2e check covered phone → Mac).
  Steps: `poc/spikes/menubar/FOLLOWER.md`.
- **Real-phone screenshots** for the README (home + chart, widgets, settings, share): synthetic data or cropped, never
  the user's health data.
- Not built: printable recovery sheet (docs/06).
- Needs the user: Proton Pass / Bitwarden save+restore on the real phone, real-camera QR scan.
- **Two sensors at once** (user, 2026-10-18): Sukrara follows one sensor; pairing replaces it. Juggluco can keep
  several sensors connected, so the user can pair (and warm up) the new one before the old one ends, with no gap.
  Then the "change it before bed" reminder could say "pair the new one now". Where Juggluco does it:
  `third_party/Juggluco/Common/src/main/java/tk/glucodata/SensorBluetooth.java` (`gattcallbacks`: one GATT callback
  per active sensor, filled by `setDevices(Natives.activeSensors())`; `removeDevice` drops one by serial), with the
  list from `activeSensors` / `setusedsensors()` in `Common/src/main/cpp/g.cpp`.
- Leftover test state: the `medium_phone` emulator wearer has test followers ("n5 small follower" …); harmless.

## 3. Phone settings to restore when development ends

With the user's OK (2026-10-16) the phone stays awake on USB: `settings global stay_on_while_plugged_in` 0 → 2,
`settings system screen_off_timeout` 30000 → 1800000. Restore both (0 and 30000) when development is finished.

## 4. Incident fixed 2026-10-16 — keep it fixed

Sukrara's own Garmin/xDrip server once grabbed Juggluco's port 17580 while Juggluco's web server was briefly down,
and Sukrara's Juggluco source then read its own stale server. Fixed in `bridge/LocalServers.kt` (never bind 17580
while the source is Juggluco) and `owner/GlucoseSource.kt` (refuse entries whose `device` is "Sukrara"). If it
ever happens again: Juggluco → Settings → Web server → untick/tick *Active*.

## 5. Rules and know-how for whoever continues

- **Phone** Pixel 10 Pro `62240DLCH0005W` (USB). The user relies on Sukrara + Juggluco daily: only in-place updates
  (`adb -s … install -r`, keeps data), read-only adb, the DebugReceiver
  (`am broadcast -n app.mauri.sukrara.poc/app.mauri.sukrara.features.DebugReceiver …`, see docs/10). **Never** take the
  sensor from Juggluco without the user's OK, never enable/post to **LibreView** (user's real doctor-facing account;
  a test treatment must be deleted again — a deleted, never-uploaded entry is never sent), never uninstall Sukrara /
  clear its data (identity + followers), no reboots, no global Wi-Fi/BT toggles. Always `adb -s <serial>`. Keep the
  user's status-bar settings (chip, icon, hook, placement, colour, hide icon) as found. SystemUI work:
  `poc/spikes/statusbar-android/HANDOFF.md` (safety procedure).
- **One build line on the phone**: several sessions/agents build Sukrara; every phone install must contain all
  merged work (merge the integration branch first), or features silently disappear from the user's phone. Install
  with **`tools/install.sh phone`** (and `tools/install.sh mac`; `--dry-run` first): it refuses a dirty tree, a HEAD
  without origin/main, the branches in `tools/check/integration.txt` or the commit installed last time, and failing
  `tools/check/run.sh`; it builds the release, updates in place (`install -r`), reads `versionName` back and records
  the commit in `<git common dir>/sukrara-installed-phone|mac`. Keep `integration.txt` on the round's branch.
- **Guards**: `tools/check/run.sh` (rules from CLAUDE.md as checks; `--layout` runs the cross-surface tests), hooks via `tools/hooks/install.sh` (`core.hooksPath` must be the relative `tools/hooks`: an absolute path into a checkout without `tools/` silently disables them), CI
  `.github/workflows/checks.yml`, manual surface checks in `docs/QA.md`.
- **Android**: Kotlin only — never Java source; newest APIs; GPL header on every file; sign debug + release with
  `~/.android/debug.keystore`. JDK 17: `export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home`.
  Watch R8 (widget classes merged once; keep rules in `proguard-rules.pro`) — test release builds after a restart.
- **Strings** (English + Spanish): add every user-visible text to `app/src/main/res/values/strings.xml` (snake_case
  name grouped by screen, `<!-- what it is [CHAR LIMIT=…] -->` above it, `%1$s` positional arguments, `<plurals>` for
  counts, `translatable="false"` for brand names and units) and the same key to `values-es/strings.xml` (Spain
  Spanish; terms: hidratos, insulina rápida / lenta, tiempo en rango). Compose: `stringResource`; elsewhere
  `ctx.getString`. Plain-Kotlin drawing code (card, graph, badge) reads `core/Words` (English defaults for the JVM
  tests, loaded from the resources in the app) — add the field there too. `StringsParityTest` fails on a missing key
  or argument. The alert engine stays English (scenarios); the notifier words its headlines (`AlertNotifier.headline`).
  Enum and option labels on the settings pages resolve in `ui/SettingsLabels.kt` (models keep no text).
- **Mac self-tests and dev runs**: run a self-test only after a build that succeeded (`swift build && .build/debug/GlucoseBar
  -v2selftest`, or `set -e`). A failed build leaves the previous binary, and on 2026-10-19 such a stale binary
  didn't know the new flag and started as a second Sukrara on the user's real Keychain item, data files and phone. The
  binary now refuses that (`LaunchGuard`, `-argsselftest`, CI): an unknown `-…selftest`, a bare unknown `-flag` or a
  stray word exits 2, and a dev build (`swift build` output, or an app inside the repo like `build/Sukrara.app`)
  starts the full app only with `SUKRARA_DEV=1` (exit 3 otherwise). With `SUKRARA_DEV=1`, also pass
  `-keychainService <test> -readingsDir <dir> -treatmentsDir <dir>` to keep the real data out. **Snapshot and dev runs are offline** (2026-10-21: a
  `-settingsSnapshot` dev run joined the user's phone over Bluetooth): `LaunchGuard.services` starts no Bluetooth,
  relays, LAN feed or alert engine for any `-…Snapshot` / `-preview` / `-panelPreview` / `-openPanel` /
  `-openTreatments` run or any dev build (`SUKRARA_DEV=1` included); main.swift starts transports only through it and
  `-argsselftest` fails otherwise. Test networking with the self-tests (`-v2selftest`, `-v2relaytest`), never a dev run.
- **Mac**: `poc/spikes/menubar`, `./bundle.sh` → `build/Sukrara.app` (`AD_HOC=1` = no widgets), then `./dmg.sh` →
  `build/Sukrara-<ver>.dmg` (needs `uv tool install dmgbuild`). **Releases attach the dmg** (what the README's
  Download button and the site link to) plus `Sukrara-mac-<ver>.zip`; bump both links in README.md and docs/index.md. Publishing a release also updates the Homebrew cask (`mauriciabad/homebrew-tap`, `.github/workflows/homebrew-tap.yml`; needs the `HOMEBREW_TAP_DEPLOY_KEY` secret, docs/20). The user's daily
  copy is **`~/Applications/Sukrara.app`** (login item, team-signed with the user's Apple Development cert, with the
  widget appex). Don't overwrite it with test builds — use another path, and relaunch it afterwards so the user keeps
  glucose in the menubar (a different signature means one more Keychain prompt). Logs: `~/Library/Logs/Sukrara.log`. **After replacing the app, restart its widget extension**: the running `SukraraWidget`
  process keeps the old code (seen 2026-10-17: a 0.11.4 extension couldn't read 0.11.7's widget file, so the widgets
  stopped updating). `pkill -f SukraraWidget.appex` (chronod starts the new one on the next reload, ≤ 1 min), and
  `pluginkit -mAv | grep -i sukrara` should list only `~/Applications/Sukrara.app`; unregister worktree builds with
  `pluginkit -r <path>/SukraraWidget.appex`. **Notarized release** (needs the paid Apple Developer Program; until then
  users click Open Anyway, README): once, create a *Developer ID Application* certificate (Xcode → Settings → Accounts
  → Manage Certificates → +) and `xcrun notarytool store-credentials sukrara --apple-id … --team-id … --password
  <app-specific password>`; then `SIGN_ID="Developer ID Application: … (TEAMID)" NOTARY_PROFILE=sukrara ./bundle.sh &&
  NOTARY_PROFILE=sukrara ./dmg.sh` (notarizes + staples the app, then the dmg; writes the release zip too). No
  provisioning profile: the `<TEAMID>.app.mauri.sukrara.poc` App Group is authorised by the Team ID.
- **Repo**: public, GPL-3.0-or-later; repo-local noreply identity; never commit `secrets/`, keys, APKs, build dirs;
  scan staged files for `nsec1…`; crop screenshots so no other apps' notifications are committed. Site:
  https://sukrara.mauri.app (GitHub Pages from `main:/docs`).
- **Orchestration that worked**: one worktree per agent from the integration branch, a shared interface committed
  before dispatch (`TreatmentFeed`), the protocol note committed early so the Mac agent could build against it,
  phone ownership handed over explicitly between sessions, at most two emulators at once (three overloaded the Mac).
