# CGM on the Mac — research spikes

Research repo for a **macOS menubar app that shows live FreeStyle Libre 3 glucose** (number + a precise trend arrow)
**without the cloud** (no LibreView / LibreLinkUp), and for a future **Android app that replaces Juggluco**.
Each investigation is a set of throwaway proofs of concept plus a written report; the code here is meant to be read and
learned from, not shipped.

<p>
  <img src="docs/img/menubar-button.png" alt="Menubar item: number and trend arrow" height="44">
  &nbsp;
  <img src="docs/img/menubar-sparkline.png" alt="3 h sparkline from the menubar dropdown" height="70">
</p>
<img src="docs/img/trend-arrows.png" alt="Trend arrow for every rate from −4 to +4 mg/dL/min, light and dark menubar">

<sub>The arrow angle is continuous in the sensor's rate of change (0 → flat, ±3 mg/dL/min → vertical, double chevron
beyond) instead of Libre's 5 fixed arrows. Colours follow personal thresholds 65 / 145 / 185 mg/dL.</sub>

## Investigations

| # | Date | Topic | Report |
|---|---|---|---|
| 01 | 2026-10-15 | Getting live glucose onto a Mac: direct sensor BLE, Juggluco over Wi-Fi, and Bluetooth when there's no shared network | [docs/01-connectivity.md](docs/01-connectivity.md) |

## Current conclusions

| Question | Answer |
|---|---|
| Can a Mac read the Libre 3 sensor directly over Bluetooth? | **Yes, proven.** It authenticated and decrypted live 1-minute readings using Juggluco's open-source C port of the Libre 3 security. But a phone must still start the sensor (NFC) and hand over its pairing secrets, and the sensor talks to **one** device at a time — so it can't run alongside the phone. |
| Can Juggluco feed the Mac without internet? | **Yes, proven** over Wi-Fi (auto-discovery, ~0.3 s latency) and USB. Requires Juggluco's web server with "Local only" off. |
| …with **no shared network** (Mac offline, or Mac on home Wi-Fi + phone on mobile data)? | **Yes, proven over Bluetooth LE** with a small companion app (GlucoBridge) that relays Juggluco to the Mac: every reading delivered within ~1 s, also with the phone on mobile data and with the phone unplugged/screen-off for 17 min. |
| Recommended architecture | **Phone app = sensor reader + BLE GATT server; Mac app = BLE central** (Wi-Fi as a secondary transport). PoC 1 uses Juggluco as the sensor reader today; PoC 2 (next) makes the phone app read the sensor itself. |

## Try it (PoC 1, works today with Juggluco)

Downloads are on the [Releases page](https://github.com/mauriciabad/cgm-reading-mac-spike/releases).

1. **Phone**: Juggluco connected to your Libre 3, with *Settings → Web server* **Active** ("Local only" may stay on).
   Install `GlucoBridge-*.apk`, open it, allow *Nearby devices* + *Notifications*. It shows the latest value and
   "BLE advertising: on".
2. **Mac** (Apple Silicon, macOS 14+): unzip `GlucoseBar.app.zip`, then
   `xattr -dr com.apple.quarantine GlucoseBar.app` (it's ad-hoc signed) and open it. Allow Bluetooth (and Local
   Network if you also want the Wi-Fi transport). The number appears in the menubar within a few seconds.

<img src="docs/img/glucobridge-phone.png" alt="GlucoBridge on the phone" width="260">

Not a medical device — see the [disclaimer](#disclaimer).

## Repository layout

```
docs/                     reports (one per investigation), images, raw evidence logs
spikes/
  menubar/                GlucoseBar — macOS menubar app (Swift, AppKit, CoreBluetooth). Wi-Fi + BLE transports
  glucobridge-android/    GlucoBridge — Android companion (Kotlin): Juggluco on localhost → BLE GATT server
  ble-scan/               tiny BLE advertisement scanner
```
The direct Mac ↔ sensor experiment is summarized in the report but its code is not published.

Build instructions per spike: [spikes/README.md](spikes/README.md).

## Disclaimer

- **Not a medical device.** Research prototypes; values may be wrong, late or missing. Don't make treatment
  decisions or rely on alarms from anything in this repo.
- FreeStyle Libre is a trademark of Abbott. This project is not affiliated with Abbott or with Juggluco.
- The direct-sensor experiment was run on a sensor the author owns, for interoperability with their own data; its
  code and sensor pairing data are not part of this repo. [Juggluco](https://github.com/j-kaltes/Juggluco) (GPLv3) is
  used as a separate app; none of its code is included here.
- License for this repo's own code: not chosen yet.
