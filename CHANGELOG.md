# Changelog

Sukrara's releases. 0.x versions are previews (GitHub prereleases).

## 0.12.0 — first release (GitHub prerelease)

Untested on a real sensor yet. Install and the first-hour checks: the
[v0.12.0 release](https://github.com/mauriciabad/sukrara/releases/tag/v0.12.0).

- **Apps** for Android and Mac (`apps/`), tested against shared specs and fixtures (`contracts/`): the alert rules
  (timeline scenarios), the card renderer, protocol v2 and the settings schema. App id `app.mauri.sukrara` on both.
- **Android**: Libre 3 / 3 Plus read directly (NFC start / takeover with the LibreView account's receiver ID, then
  Bluetooth) or from Juggluco; home, live notification + Live Update chip, alerts with mute / unmute, treatments
  (dialog, Quick Settings tile), home-screen widgets, settings for daily use with an alert reliability checklist,
  sharing to followers over Nostr with a restorable identity (recovery code). Signed with Sukrara's own release key.
- **Mac**: menu bar value and panel, following by invite / QR / nearby Bluetooth, alerts with sound packs, treatments,
  settings, desktop widgets. Signed by team QWUV9H4A3G, not notarized (Open Anyway once).
- **Every app**: checks GitHub releases for updates; 0.x builds are offered prereleases.
- **Not included**: following on Android and Bluetooth sharing from the phone, uploads (Nightscout, Tidepool,
  LibreView) and Health Connect / broadcasts, Garmin, root / LSPosed status-bar and lock-screen features, pens,
  reminders, report / CSV export, settings export, untested sensors (Dexcom, Aidex, SmartGuide).
