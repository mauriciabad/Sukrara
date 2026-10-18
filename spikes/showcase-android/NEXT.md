# Sukrara showcase — what's left (handoff for the next orchestrator)

*Updated 2026-10-16 ~15:00 after polish round 2 (docs/10 "Polish round 2").* The showcase is **Sukrara for Android**
(`spikes/showcase-android`, `app.mauri.sukrara.poc`) and **Sukrara for Mac** (`spikes/menubar`, `Sukrara.app`, same
id). Read `docs/10-showcase.md` first (features, defaults, DebugReceiver table, gap audit) and `README.md`.

## 1. Done in polish round 2 (for reference)

All five user requests of the previous NEXT.md, plus three new ones, are built, merged and on the phone + Mac:
follower writer gating with sent/accepted status (Android + Mac), treatments for followers (Nostr + BLE, Android +
Mac, follower Nightscout API), treatments drawn in every chart, the Live Update chip as value + arrow, the expanded
card (MetricStyle with the chip, custom graph card without), the chart under the value on the home screen, hiding
Sukrara's notification icon (root/LSPosed), Mac one-Bluetooth-link fix, thin-history fix, Home Assistant re-test,
Garmin state label. The Juggluco-port fix (§4 below) ships with this round.

## 2. Open

- **Match code UX**: done in 0.11.6 (the user's proposal: single-use links, no code; Allow dialog with the invite
  label; nearby pairing keeps a typed code). See docs/10 "Single-use invites".
- Investigation 14 is in (0.11.0): short invites, private names + versioned generated pictures. Left out on purpose:
  public kind 0, Blossom, Nostr login, delete-everywhere (docs/14 "In Sukrara").
- **Thin history fix not seen end to end**: verify with a fresh follower (two emulators: wearer `medium_phone`
  debug/synthetic, follower `small_phone`; steps in docs/10 Known issues). Mac follower doesn't send `history`
  requests yet (only benefits from the approval republish).
- **Follower-side gaps**: no re-send of un-accepted writer entries when BLE reconnects (relay copies still get
  picked up); no edit/delete of own entries (Android follower; Mac has delete only); after the wearer is reinstalled
  while a follower is connected over BLE, the follower keeps a stale "connected" link until its app restarts
  (pre-existing BLE reconnect gap).
- **Follower role doesn't feed the root status bar** (system icons / LSPosed value / hide-icon switch). Battery-lab
  plan (`spikes/power/plans/sukrara.txt`) doesn't toggle `hook` / `hideNotifIcon` yet — add a window so
  investigation 11 measures their cost.
- **Mac → phone treatment over v2 on the real devices** not clicked through (needs the Mac menu); the e2e check
  covered phone → Mac. Steps: `spikes/menubar/FOLLOWER.md` / the Mac agent's report in docs/10.
- **Real-phone screenshots** for the README (home + chart, widgets, settings, share) — the phone now stays awake on
  USB, so `adb exec-out screencap` works while it's unlocked (it re-locks after a SystemUI restart).
- Not built: tier-2 Nightscout upload endpoint, Mac local reading DB, BLE abuse/rate limits, printable recovery sheet.
- Needs the user: **first real NFC scan + fresh Bluetooth pairing** (branch `n-nfc`, docs/10 "Sensor scan"; the user
  pauses Juggluco and scans in Sukrara, then scans with Juggluco to hand the sensor back), Proton Pass /
  Bitwarden save+restore on the real phone, real-camera QR scan. (macOS widgets: done 2026-10-16, hosted by Sukrara for Mac, docs/13.)
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
  `spikes/statusbar-android/HANDOFF.md` (safety procedure).
- **One build line on the phone**: several sessions/agents build Sukrara; every phone install must contain all
  merged work (merge the integration branch first), or features silently disappear from the user's phone. Install
  with **`tools/install.sh phone`** (and `tools/install.sh mac`; `--dry-run` first): it refuses a dirty tree, a HEAD
  without origin/main, the branches in `tools/check/integration.txt` or the commit installed last time, and failing
  `tools/check/run.sh`; it builds the release, updates in place (`install -r`), reads `versionName` back and records
  the commit in `<git common dir>/sukrara-installed-phone|mac`. Keep `integration.txt` on the round's branch.
- **Guards**: `tools/check/run.sh` (rules from CLAUDE.md as checks), hooks via `tools/hooks/install.sh`, CI
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
- **Mac**: `spikes/menubar`, `./bundle.sh` → `build/Sukrara.app` (`AD_HOC=1` = no widgets), then `./dmg.sh` →
  `build/Sukrara-<ver>.dmg` (needs `uv tool install dmgbuild`). **Releases attach the dmg** (what the README's
  Download button and the site link to) plus `Sukrara-mac-<ver>.zip`; bump both links in README.md and docs/index.md. The user's daily
  copy is **`~/Applications/Sukrara.app`** (login item, team-signed with the user's Apple Development cert, with the
  widget appex). Don't overwrite it with test builds — use another path, and relaunch it afterwards so the user keeps
  glucose in the menubar (a different signature means one more Keychain prompt). Logs: `~/Library/Logs/Sukrara.log`. **After replacing the app, restart its widget extension**: the running `SukraraWidget`
  process keeps the old code (seen 2026-10-17: a 0.11.4 extension couldn't read 0.11.7's widget file, so the widgets
  stopped updating). `pkill -f SukraraWidget.appex` (chronod starts the new one on the next reload, ≤ 1 min), and
  `pluginkit -mAv | grep -i sukrara` should list only `~/Applications/Sukrara.app`; unregister worktree builds with
  `pluginkit -r <path>/SukraraWidget.appex`.
- **Repo**: public, GPL-3.0-or-later; repo-local noreply identity; never commit `secrets/`, keys, APKs, build dirs;
  scan staged files for `nsec1…`; crop screenshots so no other apps' notifications are committed. Site:
  https://sukrara.mauri.app (GitHub Pages from `main:/docs`).
- **Orchestration that worked**: one worktree per agent from the integration branch, a shared interface committed
  before dispatch (`TreatmentFeed`), the protocol note committed early so the Mac agent could build against it,
  phone ownership handed over explicitly between sessions, at most two emulators at once (three overloaded the Mac).
