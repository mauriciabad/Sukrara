# 02 — The phone app reads the sensor itself (no Juggluco)

*2026-10-15 · investigation report · follows [01 — connectivity](01-connectivity.md)*

## Goal

In [PoC 1](01-connectivity.md) the phone companion (GlucoBridge) got its data from Juggluco's local web
server. That means two apps, an HTTP server on loopback, and a dependency on an app whose UI the user wants to
replace. **PoC 2: GlucoBridge talks to the Libre 3 sensor itself**, and does it in a battery-conscious way.

## Result

**It works.** GlucoBridge resumed the sensor's BLE session on its own, decrypted the 1-minute glucose packets and
pushed them to the Mac — with Juggluco force-stopped the entire time.

| | |
|---|---|
| First reading | 2026-10-15 19:59:47 — **83 mg/dL, −0.59 mg/dL/min**, delivered to the Mac the same second |
| Steady state | a reading every minute for 7+ minutes, no disconnects, Mac latency ~1 s |
| History | the sensor's own stored 5-minute values: **34 points (~3 h) in about a second** on connect |
| Correctness | values matched Juggluco's own backfill exactly (80, 82, 83, 84 at 20:09–20:13) once it took the sensor back |
| Sensor state | Juggluco reconnected afterwards and filled its gap by itself |

Architecture now:

```
Libre 3 sensor ──BLE──▶ GlucoBridge (Android) ──BLE GATT──▶ GlucoseBar (Mac)
```

No Juggluco, no HTTP server, no network of any kind.

![GlucoBridge reading the sensor directly](img/glucobridge-direct.png)

*99 mg/dL at +0.40 mg/dL/min, 36 readings over 3 h — all from the sensor. "BLE advertising: off · Mac subscribed: 1"
is the power optimisation below doing its job: a client is connected, so the phone stops advertising.*

## How it is built

Two Gradle **product flavors** share everything except where glucose comes from, so PoC 1 stays runnable:

| Flavor | Source | Build |
|---|---|---|
| `juggluco` | Juggluco's web server on `127.0.0.1:17580` (PoC 1) | `./gradlew assembleJugglucoDebug` |
| `direct` | the sensor itself (PoC 2) | `./gradlew assembleDirectDebug` |

Shared `src/main` holds the Mac-facing GATT server, the foreground service and the UI; each flavor provides
`createGlucoseSource(context)`. Everything is Kotlin; the only native code is the sensor's security core
(C, called through a small JNI layer).

Pairing data (which sensor, and the credentials to resume its session) is read from a private file in the app's
data directory. For the spike it is imported once from the app that activated the sensor; the real app will
produce it during its own NFC activation. It is a per-sensor secret and never leaves the phone.

## History

A sensor-reading app starts with nothing and would take hours to fill a chart, because each packet only carries
the current minute. The sensor also stores a value every 5 minutes, but it only sends them when **asked**.

On connect GlucoBridge now reads the sensor's current position from its status packet, then requests the last
3 hours of stored values. They arrive as a short burst of notifications (6 values each) and are merged into the
same series as the live readings. Cost: well under a second.

This is the one place the app writes to the sensor — the request is the same one Juggluco sends on every
connection, so it is routine traffic, not a state change. Everything else is listen-only.

## Battery

Design choices, and what they are worth:

| Choice | Why |
|---|---|
| **No scanning.** Connect to the sensor by address with `autoConnect`, so the Bluetooth controller does the waiting. | Measured: **zero BLE scan time** attributed to the app in `batterystats`. App-level scanning is one of the most expensive things a BLE app can do. |
| **No wakelocks.** The notification callback does the work and returns. | Measured: **no partial wakelocks** held by the app. For comparison, Juggluco took **973 partial wakelocks** (~16 s cumulative) in the same battery session — it holds one for up to 30 s per reading. |
| **Advertise only while no Mac is subscribed**, in `ADVERTISE_MODE_LOW_POWER`. | The Mac scans continuously, so a slow advertising interval costs nothing; advertising to nobody is pure waste. See the trap below. |
| **One persistent sensor connection** instead of connect-per-reading. | See the finding below — reconnecting is far more expensive than idling. |
| History blob built on demand, not per reading. | Trivial, but it keeps the per-minute path to "decrypt, 8-byte notify". |

### Finding: trimming subscriptions backfires

The sensor exposes seven notifying characteristics; GlucoBridge only parses two. Subscribing to just the ones we
use looked like an easy saving. It is not:

| Subscriptions | Behaviour |
|---|---|
| Only the ones we parse | Sensor **dropped the link at exactly 60 s**, every time → reconnect loop, **one reading per 2 minutes** (half the data, plus a full reconnect and re-handshake each time) |
| All seven (what Juggluco does) | **Stable**, one reading per minute |

The sensor runs a full data cycle every minute and does not tolerate a partially subscribed client. Staying
subscribed to everything is both more reliable *and* cheaper than the reconnect loop it causes. The code keeps the
trimmed set behind a flag plus an automatic fallback, so a bad choice self-heals instead of silently halving the data.

**Caveat:** that test also changed the connection interval at the same time (a low-power interval was requested in
the failing run, not in the passing one), so the 60 s drop is not isolated to the subscription set — only strongly
implied by its exact 1-minute period. Worth isolating before treating it as settled.

### Not yet measured

Real battery drain. `batterystats` only attributes power while the phone is **discharging**, and the phone was on
USB for the whole session (needed for the test tooling). A proper A/B — same hours, screen off, unplugged, PoC 1 vs
PoC 2 vs Juggluco alone — is the obvious next step. The CPU totals in `batterystats` are not comparable as they
stand: they cover different time windows, and GlucoBridge's include Compose UI startups.

### Trap: your own sensor link looks like an incoming client

Stopping the adverts while a client is connected broke the Mac's ability to find the phone at all. Android reports
the phone's **outgoing** connections — the sensor — through the *same* GATT-server callback as incoming clients, so
connecting to the sensor made the app believe a Mac had arrived.

The fix is to ignore connection events entirely and key off the subscription write, which only a real client of ours
performs. The logs now distinguish the two peers plainly. Worth
remembering for the real app — on Android, "a device connected to my GATT server" does not mean what it sounds like.

## Also fixed: the Mac could go deaf without noticing

During the stable run the phone kept notifying every minute to a subscribed central, but the Mac stopped showing
new values after ~2 minutes — with **no disconnect callback**. CoreBluetooth left the peripheral in `.connected`
while the link was actually gone.

GlucoseBar now treats silence as a disconnect: if a connected bridge sends nothing for 150 s (readings are
1/minute), it drops the peripheral and rescans. Worth remembering for the real Mac app — "still connected" is not
evidence that data is flowing.

## Fresh activation audit (Libre 3 / 3 Plus, 2026-10-18)

Before the user's first new-sensor start with Sukrara (until now only a takeover had run on a real sensor), the NFC
start path was checked against Juggluco (`Common/src/libre3/java/tk/glucodata/libre3/NFC.java`, `cpp/libre3/nfc.cpp`,
`cpp/libre3/dp_activation.hpp`):

- **Command**: `02 A0 7A` + LE32(now − 1) + LE32(receiver ID) + CRC-16 (poly 0x1021, init 0xFFFF, input reflected,
  output not) over those 8 bytes, little-endian — identical (`DPGetActivationCommandData`). `Libre3NfcTest` checks the
  three `static_assert` CRCs and Juggluco's logged payload `8C 42 86 62 8D 6D 41 1F BC 93`. Start vs takeover = patch
  state 1 (`nfc1[17] == 1`), as Juggluco.
- **Receiver ID — fixed**: Sukrara fell back to its own random ID when no LibreView account was known; a sensor
  started that way could never be taken over by another app. Now a new sensor only gets the LibreView-derived ID
  (Juggluco's default `getlibreAccountIDnumber`; its opt-in "New Libre app" ID from Abbott's newer receiver API is
  not used), and nothing is sent until it is known.
- **Answer**: 19 bytes `00 | A5 00 | BD address ×6 (reversed) | PIN ×4 | activation time LE32 | CRC`, time ≤ 0 → now;
  errors `00 A5 01 <code>` (0xB1 = wrong receiver ID; states 6/8 = ended) — identical (`nfc2`, `nfc2error`).
- **Interrupted tap**: the command is retried only on transient transceive errors (as Juggluco's `wholenfccmd`); a
  lost tag aborts. A start that reached the sensor but lost its answer leaves it bound to the LibreView ID, so the
  next tap (state ≠ 1 → `A8` with the same ID) gets the pairing back, with the sensor's own activation time.
- **First Bluetooth connection**: like Juggluco (`sendstreaming` + a new `libre3stream` right after the NFC answer),
  Sukrara connects at once (autoConnect) and runs the fresh pairing; during warm-up the client waits for its end.
- **Sensor life**: start = the activation time in the answer, warm-up = patch info byte 13 × 5 min, wear = patch
  info LE16 at +6 → `SensorLife` via `onSensor`.

## Libre 2 / 2 Plus: investigated, discarded (2026-10-18)

Asked when the user's Libre 3 Plus ended and a European Libre 2 Plus was the next sensor. Dropped by the user: an old
sensor with a falling share, not worth the difficulty below. What Sukrara does instead: an NFC tap on a Libre 1 / 2 /
2 Plus (UID maker byte 0x07, Texas Instruments; the Libre 3 is 0x7A, Abbott) sends **nothing** to the sensor and says
*Libre 2 sensors aren't supported* with a button to Settings → Glucose source (Juggluco). Only a 0x7A chip ever gets a
Libre 3 command (`Libre3Nfc.chip`, `Libre3NfcTest.chipGate`).

- **The crypto is open.** DiaBLE (MIT; also LibreTools, LibreTransmitter) has the reverse-engineered gen-1 (EU)
  scheme: activation `A1 1B`, streaming enable `A1 1E`, FRAM and BLE decryption, the BLE unlock payload. A Kotlin port
  matched DiaBLE's own Swift bit for bit on generated vectors (kept on branch `sl-libre2-open-archive`, not merged).
  EU Libre 2 Plus patch info `C6 09 31 01` / `7F 0E 31 01` = security generation 1; US/Canada "Gen2" is closed.
- **mg/dL is not.** The Libre 2 sends raw sensor counts. Official values need Abbott's algorithm in LibreLink's
  `libDataProcessing.so`; the open alternative (DiaBLE / xDripSwift "factory calibration" from the FRAM constants)
  is an unvalidated approximation — not good enough to dose from.
- **Using Abbott's library like Juggluco** (it ships a copy: `Juggluco-11.5.0-arm64.apk` → `lib/arm64-v8a/libcalibrat2.so`,
  from LibreLink builds per `mklibs.sh`): the copy is binary-patched so imports land in Juggluco's hooks (`getTimeofday`,
  `pthread_Detach`; `share/hooks.cpp` `Fork`, `Pipe`, `FOpen`, `d1sym`, …); it has only `JNI_OnLoad`, run against a fake
  JavaVM/JNIEnv (`jnisub.cpp`) whose `RegisterNatives` collects the entry points (`libre2.cpp:145-200`); each call needs
  a fixed 72-byte token (`libre2.cpp:61`); its `pm path` install check gets a faked answer; and its root / debugger /
  syscall checks run under Juggluco's ptrace "debug clone" (`debugclone.cpp`, 2258 lines) — which matters on this
  rooted phone. Re-creating that layer is ~5–8k lines of fragile C++, testable only on a device, and an anti-tamper
  trip tends to give wrong values rather than a crash. Several days with an uncertain outcome.
- **xDrip+** doesn't decode the Libre 2 either: it hands the data to the closed OOP2 app (`LibreOOPAlgorithm.java:90-183`)
  or takes glucose broadcasts from a patched LibreLink / Juggluco (`LibreReceiver.java`).

## Open questions / next PoCs

- [ ] Isolate the 60 s drop: subscription set vs connection interval, one variable at a time.
- [ ] Real unplugged battery A/B (PoC 1 vs PoC 2 vs Juggluco), screen off, several hours.
- [ ] Verify the Mac watchdog actually recovers the link (the fix is written, not yet observed firing).
- [ ] Sensor **activation** from the app (NFC) — currently pairing data is imported from another app, so a brand
      new sensor still needs one.
- [ ] Persist history across app restarts (it is in memory only; a restart re-fetches 3 h from the sensor).
- [ ] Secure the phone↔Mac link (still unauthenticated) and survive Mac sleep.
- [ ] Long soak: deep Doze, Bluetooth toggles, out-of-range and back.

## Reproducing

```sh
cd poc/spikes/glucobridge-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home PATH="$HOME/.local/bin:$PATH"
./gradlew assembleDirectDebug
android install --apks=app/build/outputs/apk/direct/debug/app-direct-debug.apk --install-options=-g
./tools/import-pairing.sh            # one-time, needs root; imports this sensor's pairing data
adb shell am force-stop tk.glucodata # the sensor serves one app at a time
adb shell am start -n dev.spike.glucobridge/.MainActivity
```
Afterwards, relaunch Juggluco to hand the sensor back; it reconnects and backfills the gap on its own.

Toolchain added in this investigation: NDK 28.2.13676358 and CMake 4.1.2 (`android sdk install ndk/… cmake/…`).
