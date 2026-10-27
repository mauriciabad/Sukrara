<p align="center">
  <img src="assets/brand/sukrara/macos/AppIcon-1024.png" alt="Sukrara icon" width="120">
</p>

<h1 align="center">Sukrara — your sugar, now</h1>

<p align="center">
  <a href="https://github.com/mauriciabad/Sukrara/releases"><b>Releases</b></a>
  &nbsp;·&nbsp;
  <a href="docs/FEATURES.md">What it does, in detail</a>
  &nbsp;·&nbsp;
  <a href="https://sukrara.mauri.app">sukrara.mauri.app</a>
</p>

Sukrara shows live glucose from a **FreeStyle Libre 3** on your **Android phone** (read straight from the sensor) and
on the **Mac menu bar** of the people who look after you (or your own). It alerts you when glucose goes low or high or
stops arriving, lets you log insulin and carbs in seconds, and shares readings **end-to-end encrypted** over public
Nostr relays or Bluetooth nearby. There's no cloud account to create and no server to host. The name is
Catalan-ish: *sucre* (sugar) + *ara* (now).

> **Sukrara V1, 0.90.x: release candidates.** V1 is a clean rewrite of the Sukrara showcase (0.11.x, the PoC built
> from research spikes, now frozen in [`poc/`](poc/)). 0.90.x versions are GitHub **prereleases** and haven't been
> tested on a real sensor yet: keep the showcase installed as your fallback until V1 has run a few days for you (see
> [Switching from the showcase](#switching-from-the-showcase)). **Not a medical device** (see the
> [disclaimer](#disclaimer)).

## What it does today

The full catalog, with every rule and number, is [docs/FEATURES.md](docs/FEATURES.md). In short:

**Android (you wear the sensor)**
- **Reads the Libre 3 / 3 Plus directly**: hold the phone to the sensor to start a new one or take over a running one
  (NFC), then Sukrara keeps it connected over Bluetooth by itself, with 24 h backfill and automatic reconnects. It
  uses your **LibreView account's receiver ID** (sign in once, only to get the ID), so a sensor can move between
  Sukrara, the showcase and Abbott's app. Or read from **Juggluco's local web server** if Juggluco keeps the sensor.
- A guided **new sensor** screen: where the NFC antenna is, warm-up countdown, every failure handled on the same screen
  (ended sensor, Libre 1/2, another app holding the sensor, Bluetooth off).
- **Settings › Sensor**: the current sensor (status, serial, start, end and time left), the reading source (Libre 3
  directly or Juggluco, saying first what changes), the LibreView account (email and receiver ID, sign in / out; the
  password is never stored) and *Pair new sensor*.
- **Home screen**, glucose first: the value, trend arrow and a 6 h chart you can scroll back, the sensor's end date,
  one banner at a time for what needs you (monitoring stopped, alerts may not ring, turn off Sukrara Showcase,
  damaged saved data, finish setup), and the last 24 h of treatments with delete + undo. Never a bare "---": when
  there's no value it says why and what to do.
- A **live notification** that is also a **Live Update chip** next to the clock (Android 16 QPR2+), with the same
  freshness steps as the card: from 5 min the number is struck through, from 20 min it's gone. Tap → *Add treatment*.
- **Alerts**: very low, low, high, very high, rising / falling fast and no data; each on its own channel,
  "like an alarm" ones pass Do Not Disturb. *Mute…*, *Remind less*, *Unmute* and *Change time* on the notification,
  in the app and with TalkBack; *Mute all* with a visible strip to undo it. The no-data check runs from an exact alarm,
  independent of the monitoring service. A *Sensor ends in 6 h* warning before the sensor's end.
- **Monitoring** in one foreground service that comes back by itself (boot, update, a 15 min health check); before the
  first reading every surface says *Not monitoring: no sensor yet* and no no-data alarm is armed.
- **Treatments**: a quick dialog for rapid / long insulin, carbs and a note (from the notification, the Quick
  Settings tile *Add treatment* or home), with a short countdown when opened on the lock screen.
- **Home-screen widgets** (*Glucose*, *Glucose graph*, *Glucose and graph*) drawn by the same card renderer at the
  widget's real size; home offers *Add widget* until a graph widget is placed, and a tap opens *Add treatment*.
- **Settings** for daily use: alerts (thresholds you confirm, repeats, sounds, quiet rules), an alert reliability
  checklist with *Fix* buttons (notifications, alarms, full-screen, battery, Do Not Disturb, Bluetooth), ranges,
  graph basics, sensor, and About with an update check against this repo's releases.
- **Sharing core**: your sharing identity, restorable from the showcase's recovery code so existing followers keep
  following; readings and alerts go to approved followers over Nostr relays (protocol v2, compatible with
  the showcase). If another app is publishing as the same identity, Sukrara stops and says so instead of fighting it.
- English and Spanish (Spain) everywhere.

**Mac (you follow someone, from the menu bar)**
- The value, trend arrow and mute bell in the **menu bar**, struck through when old; click for the **panel** with the
  card and chart (drawn by the same renderer rules as Android, tested against the same fixtures).
- **Follow** a wearer with an invite link, a QR code image or **nearby** Bluetooth pairing; readings, alerts and
  treatments arrive end-to-end encrypted. A setup window walks Welcome → Follow → Alerts → Notifications → Open at
  login. It also follows a phone that still runs the showcase.
- **Alerts** with sound packs and the same rules and timeline scenarios as Android; mute and unmute from the menu.
- **Treatments**: add rapid / long / carbs / note, see the phone's entries, delete your own with undo.
- **Settings**: General (following, unit, open at login, update check) and Alerts (per alert, quiet times, sounds,
  ranges); it can mirror the wearer's alert settings.
- **Desktop widgets** (Glucose, Glucose graph, Glucose + graph) with the same card; they age by themselves (struck
  through at 5 min, the graph dimmed with the reading's time, no value at 20 min) and a tap opens Add treatment.
  After switching from the showcase, remove the showcase's widgets and add Sukrara's.
- VoiceOver reads the value as one sentence with the range in words.

**Not in 0.90.0** (keep the showcase if you rely on these; see
[docs/10-showcase.md › V1](docs/10-showcase.md#v1-what-came-in-and-what-was-left-out) for why): the root status-bar
icons and the LSPosed lock-screen widget, Dexcom / Aidex / SmartGuide (untested) sensors, Garmin via the local xDrip+
server, Nightscout / Tidepool / LibreView uploads, Health Connect and broadcasts, insulin pens, the full sensor
reminders page and calendar event, the glucose report and CSV export, settings export / import, following on
Android and Bluetooth sharing from the phone, and the Mac's Juggluco-over-Wi-Fi source.

## Install

Downloads are on [GitHub releases](https://github.com/mauriciabad/Sukrara/releases) (0.90.x are marked
*Pre-release*). V1 installs **next to** Sukrara Showcase (new app id `app.mauri.sukrara`; the showcase is
`app.mauri.sukrara.poc`) and copies nothing from it: you set it up fresh.

- **Android** (Android 16+, arm64): download `Sukrara-0.90.x.apk` on the phone and open it; allow installs from your
  browser when asked. Updates install over the old version and keep your data. With
  [Obtainium](https://obtainium.imranr.dev/), turn on *Include prereleases*. V1 is signed with its own key, so it
  can't update the showcase and the showcase can't update it.
- **Mac** (Apple Silicon, macOS 15+): download `Sukrara-0.90.x.dmg` and drag **Sukrara** onto **Applications**. The app
  isn't notarized yet: the first open is stopped; click **Done**, then **System Settings → Privacy & Security → Open
  Anyway** ([Apple: open a Mac app from an unknown developer](https://support.apple.com/guide/mac-help/mh40616/mac)).
  Sukrara lives in the menu bar (no Dock icon). Homebrew only installs stable releases, so it keeps the showcase.
- **From this repo** (the owner's devices): `tools/install.sh phone|mac` builds the release, verifies it and installs
  it in place. It refuses a dirty tree, unmerged work or failing checks. On the Mac it first quits the showcase and
  moves it to `~/Applications/Sukrara Showcase.app` (never deleted), and makes V1 the `sukrara:` link handler.

## Switching from the showcase

Order matters: two apps can't own one sensor, two apps alerting means double alarms, and restoring your identity in
V1 too early cuts your followers off from the app that still has the readings. V1's first-run setup walks the phone
steps in this order; any step you skip stays on home's *Finish setup* card.

1. **Install V1 next to the showcase** (above). Open it, choose *I wear the sensor* (confirmed: one role per
   install) and allow what alerts need (notifications, full-screen alerts, alarms, battery unrestricted, alert sound,
   nearby devices).
2. **Turn off the showcase's sensor use and alerts before anything else**: in the showcase, *Settings › Sensor ›
   Stop* and turn its alerts off, or uninstall it. V1's setup asks you to confirm, and home shows *Turn off Sukrara
   Showcase* for as long as the showcase is installed.
3. **Take over the sensor in V1**: sign in to LibreView when asked (needed: the sensor is bound to your LibreView
   account's receiver ID) and hold the phone to the sensor. Setup waits for a real reading; until then V1 says *Not
   monitoring: no sensor yet*.
4. **Confirm your alert thresholds**: V1 never uses them silently.
5. **Only then restore your identity**: turn sharing off in the showcase (or uninstall it), then in V1 paste the
   recovery code from the showcase's *Settings › Backup › Recovery code*. V1 refuses while the showcase is installed
   unless you confirm its sharing is off. Your followers move to V1 by themselves; nobody needs a new invite. (Or
   *Start fresh* and invite everyone again.)
6. **Check your followers**: the Mac (showcase or V1) shows V1's value less than 5 minutes old. To move the Mac to V1
   too, install V1 there and follow your phone from its setup window.
7. **Turn off the showcase's Mac login item by hand**: System Settings › General › Login Items, remove *Sukrara
   Showcase* (`tools/install.sh mac` quits it and flags the item but can't remove it for you). Uninstall the showcase
   on the phone once V1 has run well for a while; until then join links ask which app to open.

**Going back if V1 fails:** stop the sensor in V1 (or uninstall V1), open the showcase and take the sensor back with
*Pair new sensor* and a tap (it works because both apps use your LibreView account's receiver ID), then turn the
showcase's alerts back on. If you restored your identity in V1, uninstall V1 before turning the showcase's sharing
back on, so the two never publish as the same identity. If the takeover doesn't work, keep the showcase on the
current sensor and switch at the next sensor change. Nothing is copied between the apps, so the showcase's data is
as you left it.

## Repository layout

```
apps/
  android/       Sukrara V1 for Android (Kotlin, Compose; Gradle root)            → apps/android/README.md
  macos/         Sukrara V1 for Mac (Swift 6, SwiftPM; menu bar app + widget)      → apps/macos/README.md
contracts/       what both apps agree on: alert rules + scenarios, card fixtures, settings schema, protocol,
                 treatments, export, sensor traces (both test suites read it)      → contracts/README.md
assets/brand/    app icons and brand art
docs/            FEATURES.md (behaviour catalog), ARCHITECTURE.md, DEVELOPMENT.md, QA.md, the investigation
                 reports (NN-*.md), v1/ (how V1 was built); also the sukrara.mauri.app site
tools/           check/ (CLAUDE.md as checks), hooks/, install.sh, release/, settings/gen.py
poc/             the frozen showcase PoC (0.11.x): spikes/ and spec/ (Open Glucose Stream); reference only
third_party/     local only (gitignored): Juggluco, the Libre 3 native core's source
```

## Build and test

Setup (JDK 21, Android SDK, Xcode, the Juggluco checkout, signing) is in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md);
the rules in [CLAUDE.md](CLAUDE.md); the design in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

| What | Command |
|---|---|
| Repo rules (run before committing; the git hooks run it after `tools/hooks/install.sh`) | `tools/check/run.sh` |
| Android: one module's tests | `cd apps/android && ./gradlew :core:alerts:test` |
| Android: debug APK | `cd apps/android && ./gradlew :app:assembleDebug` |
| Android: everything CI runs | `cd apps/android && ./gradlew check` |
| Mac: build + tests | `cd apps/macos && swift build && swift test` |
| Mac: app bundle with the widget | `cd apps/macos && Bundle/bundle.sh` (`--unsigned` without the signing identity) |
| Settings code after a schema change | `python3 tools/settings/gen.py` |
| Release builds | `tools/release/android-release.sh`, `tools/release/mac-release.sh` |

Manual checks per surface: [docs/QA.md](docs/QA.md). Release history: [CHANGELOG.md](CHANGELOG.md). How V1 was
planned, built and reviewed: [docs/v1/](docs/v1/README.md). The showcase's investigations, one report each:
[docs/10-showcase.md](docs/10-showcase.md) and `docs/NN-*.md`.

## Disclaimer

- **Not a medical device.** Values may be wrong, late or missing, and alerts may not ring. Don't make treatment
  decisions or rely on alarms from anything in this repo. 0.90.x are release candidates.
- FreeStyle Libre is a trademark of Abbott. This project is not affiliated with Abbott or with Juggluco.
- The sensor is read for interoperability with the author's own glucose data. Per-sensor pairing data is a secret
  and is never committed here.
- The direct-sensor code also compiles [Juggluco](https://github.com/j-kaltes/Juggluco)'s GPLv3 sources, which are
  fetched separately and not included in this repo.

## License

[GPL-3.0-or-later](LICENSE). Use it, change it, share it — but anything you build on it has to stay open source
under the same terms, and if you distribute a binary you have to offer its source too.

```
Copyright (C) 2026 Maurici Abad

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.
```
