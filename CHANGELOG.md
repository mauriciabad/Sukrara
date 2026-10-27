# Changelog

Sukrara V1's releases. 0.x versions are previews (GitHub prereleases). The showcase PoC's history (0.11.x) is in its
[GitHub releases](https://github.com/mauriciabad/Sukrara/releases).

## 0.90.0 — V1 release candidate (GitHub prerelease)

First release of Sukrara V1. Untested on a real sensor yet: keep Sukrara Showcase (0.11.x) as the fallback. Install,
switching and the first-hour checks: the [v0.90.0 release](https://github.com/mauriciabad/Sukrara/releases/tag/v0.90.0).

- **V1**: clean rewrites of the Android and Mac apps (`apps/`), tested against shared specs and fixtures
  (`contracts/`): the alert rules (72 timeline scenarios), the card renderer, protocol v2 and the settings schema.
- **New app id `app.mauri.sukrara`** on both platforms: installs next to the showcase and starts fresh (nothing is
  copied); the sharing identity comes over with the showcase's recovery code, so followers keep following.
- **Android**: Libre 3 / 3 Plus read directly (NFC start / takeover with the LibreView account's receiver ID, then
  Bluetooth) or from Juggluco; home, live notification + Live Update chip, alerts with mute / unmute, treatments
  (dialog, Quick Settings tile), home-screen widgets, settings for daily use with an alert reliability checklist,
  sharing to followers over Nostr. Signed with Sukrara's own release key.
- **Mac**: menu bar value and panel, following by invite / QR / nearby Bluetooth, alerts with sound packs, treatments,
  settings, desktop widgets. Signed by team QWUV9H4A3G, not notarized (Open Anyway once).
- **Both**: check GitHub releases for updates; 0.x builds are offered prereleases.
- **Not in 0.90.0** (still in the showcase): following on Android and Bluetooth sharing from the phone, uploads
  (Nightscout, Tidepool, LibreView) and Health Connect / broadcasts, Garmin via the local xDrip+ server, root /
  LSPosed status-bar and lock-screen features, pens, reminders, report / CSV export, settings export, untested
  sensors (Dexcom, Aidex, SmartGuide). Reasons: [docs/10-showcase.md › V1](docs/10-showcase.md#v1-what-came-in-and-what-was-left-out).
