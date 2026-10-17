---
title: Sukrara (alpha)
---

<img src="https://raw.githubusercontent.com/mauriciabad/cgm-reading-mac-spike/main/spikes/brand/assets/sukrara/macos/AppIcon-1024.png" alt="Sukrara icon" width="96">

# Sukrara — your sugar, now

Live glucose from a **FreeStyle Libre 3** on your **Mac menubar**, **Android status bar and home screen**, **Garmin
watch** and your **family's devices**. End-to-end encrypted, over Bluetooth nearby or public Nostr relays from
anywhere. **No cloud account, nothing to host.** The name: *sucre* (sugar) + *ara* (Catalan: now).

**[Download for Android](https://github.com/mauriciabad/cgm-reading-mac-spike/releases/download/v0.11.12/Sukrara-0.11.12.apk)**
· **[Download for Mac](https://github.com/mauriciabad/cgm-reading-mac-spike/releases/download/v0.11.12/Sukrara-0.11.12.dmg)**
· [All releases](https://github.com/mauriciabad/cgm-reading-mac-spike/releases)
· [Source and install steps](https://github.com/mauriciabad/cgm-reading-mac-spike#try-it)

> **Alpha, built from research spikes.** Each feature started as a proof of concept with a written report (below);
> v0.10 bundles them into one app for daily use. The real Sukrara will be rebuilt from scratch with what we learned.
> **Not a medical device:** don't make treatment decisions from it.

<p>
  <img src="https://raw.githubusercontent.com/mauriciabad/cgm-reading-mac-spike/main/spikes/showcase-android/evidence/01-home-wearer.png" alt="Sukrara home screen on Android" width="180">
  <img src="https://raw.githubusercontent.com/mauriciabad/cgm-reading-mac-spike/main/spikes/showcase-android/evidence/q1-allow-dialog-matches.png" alt="Allow dialog with the follower's name, picture and the invite's label" width="180">
  <img src="https://raw.githubusercontent.com/mauriciabad/cgm-reading-mac-spike/main/spikes/widgets-android/evidence/01-value-2x1-graph-4x2-light.png" alt="Home-screen widgets" width="180">
</p>
<p>
  <img src="https://raw.githubusercontent.com/mauriciabad/cgm-reading-mac-spike/main/docs/img/statusbar-all.png" alt="Glucose in the Android status bar" width="540">
</p>
<p>
  <img src="https://raw.githubusercontent.com/mauriciabad/cgm-reading-mac-spike/main/docs/img/menubar-button.png" alt="Mac menubar item" height="44">
  &nbsp;
  <img src="https://raw.githubusercontent.com/mauriciabad/cgm-reading-mac-spike/main/docs/img/menubar-sparkline.png" alt="3 h sparkline" height="70">
  &nbsp;
  <img src="https://raw.githubusercontent.com/mauriciabad/cgm-reading-mac-spike/main/spikes/widgets-macos/evidence/combined-medium-dark.png" alt="macOS widget render" height="120">
</p>

## Features

- **Phone:** value, continuous trend arrow and 12 h chart; status-bar chip and number icon; home-screen widgets;
  with root, the graph on the lock screen; insulin, carbs and notes (also from the lock screen) with optional LibreView upload. Source: Juggluco, or the sensor read directly.
- **Mac (Sukrara for Mac):** number and trend arrow in the menubar with a sparkline; follows over Bluetooth or Nostr
  (paste the same `https://sukrara.mauri.app/join#…` link the phone shares); the same glucose alerts as the phone.
- **Alerts:** urgent low, low, high, very high, fast changes and no data, each with your own threshold and sound.
  Highs stay quiet after rapid insulin and while already falling back into range. Video-game sound packs
  ([listen](sounds/)), a different sound each time, and a vibration per alert.
- **Sharing:** a one-time short link or small QR code that shows who is inviting; you see their name next to who you
  sent it to, then **Allow**. Names and generated pictures travel only end-to-end encrypted, never public. Followers alert when
  you do. Removing someone rotates the key; everyone else carries on.
- **Offline:** nearby pairing and following over Bluetooth, no internet needed.
- **Watch and smart home:** existing Garmin watch apps work unchanged; stock Home Assistant reads it through a
  Nightscout-compatible adapter.
- **Keys without lock-in:** back up to Proton Pass, Bitwarden or KeePassDX as a normal password; a new phone restores
  everything and followers don't notice.

## Use cases

- Glucose at a glance while working on the Mac, about 0.3 s behind the phone.
- A parent following their kid, with alarms, and approve / remove in one tap.
- No internet around: followers in the same room use Bluetooth.
- Away from home on mobile data: readings reach followers through Nostr relays in about a second.
- A bedroom light that turns red at night when glucose runs high (Home Assistant).
- The doctor keeps seeing everything in LibreView.
- Glucose on a Garmin watch face.

## Investigations

| # | Topic | Result |
|---|---|---|
| [01](01-connectivity) | Live glucose onto a Mac without the cloud | Wi-Fi ~0.3 s; Bluetooth works with no shared network |
| [02](02-direct-sensor) | The phone reads the sensor itself | No Juggluco needed, values match, no wakelocks |
| [03](03-nostr) | Nostr relays as a no-hosting Nightscout | ~1 s live, 12 h backfill in 8 s, stock Home Assistant works |
| [04](04-libreview) | Treatments and glucose to LibreView | Phone + Mac as one device, no duplicates |
| [05](05-android-status-bar) | Glucose in the Android status bar | Chip, icon, system icons and an LSPosed hook all work |
| [06](06-sharing-and-keys) | Sharing and keys without lock-in | QR + code + Allow, revoke, restore; 19/19 end-to-end checks |
| [07](07-bluetooth-followers) | Bluetooth followers and nearby pairing | Same encrypted events over BLE, fully offline, Android + Mac |
| [08](08-garmin) | Glucose on a Garmin watch | Existing watch apps work with our server |
| [09](09-brand) | Name, icon and style | Sukrara |
| [10](10-showcase) | Sukrara, the showcase app | Every feature in one app, in daily use |
| 11 | Battery cost, with and without root | *In progress* |
| [12](12-android-widgets) | Android home-screen widgets | Value, graph, graph + value |
| [13](13-macos-widgets) | macOS widgets | Running in Sukrara for Mac, live, every minute (Apple certificate needed) |
| [14](14-nostr-identity) | Nostr identity | Public profiles that don't reveal the glucose stream, 63-char invites, log in with Nostr. In the app since 0.11: short invites and private names + pictures |
| [15](15-alerts) | Glucose alerts and sound packs | Same rules on phone and Mac, followers alert with the wearer, game-style sounds and vibrations |
| [16](16-graph) | One glucose graph everywhere | Time at each level beside the chart, time-in-range rectangles and rings, six ranges, freshness instead of "time ago", per-widget settings |
| [17](17-lockscreen-widget) | Glucose on the lock screen | The graph around the fingerprint icon (root + LSPosed); log a treatment without unlocking |
| [18](18-metrics) | The doctor's report in the app | Time in ranges, average, GMI and CV with their goals, on the home screen and in widget slots |

Code, numbers and the full overview: [github.com/mauriciabad/cgm-reading-mac-spike](https://github.com/mauriciabad/cgm-reading-mac-spike)
(GPL-3.0-or-later). FreeStyle Libre is a trademark of Abbott; this project isn't affiliated with Abbott or Juggluco.
