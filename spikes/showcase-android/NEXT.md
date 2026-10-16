# Sukrara showcase — what's left (handoff for the next orchestrator)

*Written 2026-10-16 ~12:30 at the end of the session that ran investigations 03, 06, 07, 10, 12, 13 and the gap
audit.* The showcase is **Sukrara for Android** (`spikes/showcase-android`, `app.mauri.sukrara.poc`) and **Sukrara for
Mac** (`spikes/menubar`, `Sukrara.app`, same id). Latest release: **v0.10.1** (+ an unreleased Juggluco-port fix, see
below). This is polish of the showcase — not new investigations. Read `docs/10-showcase.md` first (features, defaults,
DebugReceiver table, gap audit) and `README.md`.

## 1. User requests not done yet (priority order)

1. **Followers must know whether they may add treatments.** Today the follower UI lets you enter insulin/carbs/notes
   even when the wearer hasn't made you a writer, and the entry is silently dropped by the wearer (roster `writers`,
   investigation 07). Show it in the follower UI (Android *and* Mac): disable/hide "Add entry" unless this follower's
   key is in the current roster's `writers`; if allowed, show per-entry status (sent → accepted / rejected).
2. **Status-bar chip shows two numbers.** The Live Update chip shows the app icon (which itself is the glucose-number
   icon) *and* the value → "2 numbers in the chip, looks bad". The chip should show **only the value + trend arrow**,
   no app icon. Investigate the Android 16/17 promoted-ongoing / `MetricStyle` chip API: which part comes from the small
   icon vs. the short critical text, and how to drop the icon (or use a blank/arrow-only small icon for the chip while
   keeping the number icon in the status bar if that's still wanted). See docs/05 for what was proven.
3. **Live Update notification (the expanded card) → custom layout with a graph.** Remove app icon, app title and
   labels; show glucose, trend and **a graph** in a "fancy custom layout". Check what Android 17 allows for promoted
   ongoing notifications (`MetricStyle`, custom content views / `DecoratedCustomViewStyle`, `RemoteViews` with a
   bitmap graph — reuse `widgets/GraphRenderer.kt`), and that it still qualifies as a Live Update (promotion rules).
4. **Treatments in every graph.** Insulin, carbs and notes (with amounts) drawn on every chart: app home chart
   (wearer + follower), home-screen widgets, Live Update graph (item 3), Sukrara for Mac sparkline/menu chart, Mac
   widget renders. Followers need the treatments first → item 5.
5. **Treatments for followers.** Followers can't see treatments yet (Android or Mac v2). The wearer stores accepted
   treatments (owner DB, LWW on `u`) and the protocol for treatments over Nostr is in `spikes/libreview/SPEC.md` §5
   (kind 30078, `d`/`c` from `sid`, `gk` tag, signed by a roster writer or the device key). Publish them (relays + BLE),
   follow them on Android + Mac, render (item 4), serve them from the Nightscout API `/api/v1/treatments` (already
   reads the shared log on the wearer).

## 2. Known issues from the gap audit (docs/10 "Gap audit")

- **Mac: legacy + v2 Bluetooth conflict.** With both the legacy GATT link (`6c1f0001-…`) and the v2 link
  (`7c6a0001-…`) on, the Mac's second connection to the phone sometimes times out. Each alone works; Nostr always
  works. Probably: use one BLE connection per phone (prefer v2, drop legacy when v2 is paired), or serialise connects.
- **Thin history for new followers.** A new follower sometimes gets only ~1.5 h until the wearer republishes 24 h.
  Likely some relays didn't keep a burst of chunk events. Fix idea: on a new approval, republish the last 12–24 h of
  chunks (paced) and/or serve history over BLE; verify how many hourly chunks each relay actually keeps.
- **Home Assistant not re-tested against Sukrara itself.** `spikes/nostr/ha_check.py` (Docker) proved the stock HA
  Nightscout integration against the Python follower; re-run it against Sukrara's Nightscout API (`127.0.0.1:17581`
  via `adb forward`, or LAN mode with the API secret).
- **Real-phone screenshots** (README uses emulator shots; phone is PIN-locked — ask the user to unlock or to take
  them): wearer home + 12 h chart; status bar chip + expanded notification + lock screen; the pinned widgets; Settings
  (Garmin + Nightscout API, hide the secret); Share screen (crop the QR) + approve dialog; Treatments & LibreView.
- Not built: tier-2 Nightscout upload endpoint, Mac local reading DB, BLE abuse/rate limits, printable recovery sheet.
- Needs the user: direct-sensor mode run (takes the sensor from Juggluco + its alarms — only with explicit OK), Proton
  Pass / Bitwarden save+restore on the real phone, real-camera QR scan.

## 3. Owned elsewhere / blocked

- **Status-bar root system icons + LSPosed module in Sukrara** — **done 2026-10-16** (docs/10 "Root status bar").
  Before touching System UI again read `spikes/statusbar-android/HANDOFF.md` (safety procedure). Open: the follower
  role doesn't feed them yet; the battery-lab plan (`spikes/power/plans/sukrara.txt`) doesn't toggle `systemIcons` /
  `hook` yet — add a window so investigation 11 measures their cost.
- **macOS widgets** — need an Apple-issued certificate with a Team ID (free personal team via Xcode sign-in, or a paid
  account); see docs/13. Views are ready (`spikes/widgets-macos`).
- **Investigation 11 (battery)** — status and how to continue: `spikes/power/HANDOFF.md` / `spikes/power/README.md`
  (the phone stays on USB → the rootless USB variant; DebugReceiver details in docs/10).

## 4. Incident fixed today (unreleased) — keep it fixed

At ~08:48 UTC Sukrara's own Garmin/xDrip server grabbed Juggluco's port 17580 while Juggluco's web server was briefly
down; Juggluco couldn't rebind, and Sukrara's Juggluco source then read **its own stale server** for ~25 min. Fixed in
`bridge/LocalServers.kt` (never bind 17580 while the source is Juggluco) and `owner/GlucoseSource.kt` (refuse entries
whose `device` is "Sukrara"). Built and installed on the phone 2026-10-16 ~12:20 and verified (newest reading 53 s
old, port 17580 held by Juggluco's uid); **not in a release yet** — ship it in the next release. If it ever happens
again: Juggluco → Settings → Web server → untick/tick *Active*. Cosmetic leftover: the DebugReceiver reply still says
`garminState: "off"` when the server correctly leaves 17580 to Juggluco — make it report "left to Juggluco".

## 5. Rules and know-how for whoever continues

- **Phone** Pixel 10 Pro `62240DLCH0005W` (USB; PIN-locked). The user relies on Sukrara + Juggluco daily: only
  in-place updates (`adb -s … install -r`, keeps data), read-only adb, the DebugReceiver
  (`am broadcast -n app.mauri.sukrara.poc/app.mauri.sukrara.features.DebugReceiver …`, see docs/10). **Never** take the
  sensor from Juggluco without the user's OK, never enable/post to **LibreView** (user's real doctor-facing account),
  never uninstall Sukrara / clear its data (identity + followers), no reboots, no global Wi-Fi/BT toggles. Always
  `adb -s <serial>`.
- **Android**: Kotlin only — never Java source; newest APIs; GPL header on every file; sign debug + release with
  `~/.android/debug.keystore` (Proton Pass binds saved keys to the cert; App Links `assetlinks.json` on
  sukrara.mauri.app pins this cert). JDK 17: `export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home`.
  Android CLI skill: `~/.claude/skills/android-cli/SKILL.md`. Watch R8: it merged two widget classes once (keep rules
  in `proguard-rules.pro`) — test release builds after a process restart.
- **Mac**: `spikes/menubar`, `swift build` / `./bundle.sh` → `build/Sukrara.app`; restart it after rebuilding
  (`open spikes/menubar/build/Sukrara.app`) so the user keeps glucose in the menubar. Logs: `~/Library/Logs/Sukrara.log`.
- **Repo**: public, GPL-3.0-or-later; commit identity is repo-local (noreply); never commit `secrets/`, keys, APKs,
  build dirs; scan staged files for `nsec1…` before every commit; releases are named after investigations
  (v0.10.x for the showcase). Site: https://sukrara.mauri.app (GitHub Pages from `main:/docs`).
- **Orchestration that worked**: one worktree per orchestrator, agents with explicit time boxes, phone ownership
  announced to peer sessions (`ListAgents` / `SendMessage`), coordinator commits + pushes after scanning, the
  emulator for follower tests (a follower on an emulator following the real phone gives real-data screenshots). Keep
  emulators few — three at once overloaded the Mac.
