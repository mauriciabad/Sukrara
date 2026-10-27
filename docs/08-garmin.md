# 08 — Glucose on a Garmin watch without Juggluco

*2026-10-16 · investigation report · Pixel 10 Pro (Android 17), Garmin Instinct 3, Garmin Connect 5.30.1, Libre 3 Plus*

## Goal

Feed the Garmin Connect IQ glucose apps the user already runs with **our own phone app and no other CGM app**
(no Juggluco, xDrip+ or GlucoDataHandler). The three targets:

- **Instinct3 N - PRO** watchface (watchface.io / GreenBlack, `9c13aba4-…`)
- **CGM+ BG app** (CGM+ GB glance/app, `9521d9b2-…`)
- **CGM+ BG Datafield** (`43c8e77b-…`)

Building our own watchface, app and datafield is the next spike.

## Result

**All three work with GlucoBridge alone.** GlucoBridge is the `direct` flavor from [02](02-direct-sensor.md): it
reads the sensor itself and now also serves an xDrip+-compatible web service on `127.0.0.1:17580`. Juggluco was
force-stopped for the whole test (00:44–01:11). No Connect IQ settings were changed: the watch apps stay in their
"Juggluco" mode, and our server answers the way Juggluco's does.

| Target | Seen on the watch |
|---|---|
| CGM+ BG app (glance + app) | Current value ("74 →, now") and graph. The 1 h graph showed the gap from the test restarts, then filled in |
| Instinct3 N - PRO face | Value via CGM+ GB's complication (the face itself makes no network requests) |
| CGM+ BG Datafield | Current value during an activity |
| *CGM Connect (bruder_jakob), also installed* | *Current value ("73 →"), and sensor age via `sensor_status`* |
| *CGM Gauge Widget (Roboleo), unused glance* | *Made no request at all; its data was ~279 days old, so it wasn't working with Juggluco either. Out of scope* |

## How it works

```
Libre 3 ──BLE──▶ GlucoBridge (phone) ── 127.0.0.1:17580 HTTP ◀── Garmin Connect app ◀──BLE── watch app
                 serves /sgv.json            (makeWebRequest proxy: the watch asks, the phone app fetches)
```

A Connect IQ app on the watch can't open sockets. It calls `Communications.makeWebRequest`, and the **Garmin
Connect app on the phone** performs the HTTP request and returns the JSON over Bluetooth. Garmin Connect only allows
plain HTTP to `127.0.0.1`/`localhost`; anything else must be HTTPS. That is why the whole CGM-on-Garmin ecosystem
converged on **xDrip+'s "local web service" on port 17580**. Juggluco, GlucoDataHandler and AAPS (port 28891) all
copy it. None of the three targets uses Connect IQ *phone-app messaging*: the watch always pulls.

So "no other app" means: **our app must own port 17580** and speak that dialect. Garmin Connect itself stays; it
is the only bridge to the watch.

## What the watch asks for

Every request was logged by the server ([evidence](evidence/08-garmin/watch-requests.log)). Three fixed patterns
repeat every 5 min, a few seconds apart, all with `user-agent: Mozilla/5.0 ( compatible )` and
`x-app-ver: 11657` (Garmin Connect's build, not the watch app's):

| Request | Content-Type sent | Likely app | Asks for |
|---|---|---|---|
| `GET /sgv.json?count=72&brief_mode=true&interval=300` | form-urlencoded | CGM+ GB (feeds the face and datafield through its complications) | 6 h at 5 min |
| `GET /sgv.json?all_data=Y&count=72.0&sensor=Y&interval=600&brief_mode=true` | application/json | CGM Connect | 12 h at 10 min + sensor age |
| `GET /sgv.json?all_data=Y&count=12&sensor=Y&interval=300&brief_mode=true` | application/json | CGM Connect | 1 h at 5 min + sensor age |

The app attribution comes from the documented parameters and the polling cadence. Garmin Connect doesn't log which
app made a request.

- **`interval` is Juggluco's parameter, not xDrip's.** It sets the minimum spacing between returned readings. The
  apps are in "Juggluco" mode, so they send it.
- **`count=72.0`: Monkey C sends floats.** Juggluco's parser stops at the `.`. A strict integer parser silently
  fell back to the default 24 (our first version did, and returned 2 h instead of 12 h).
- **Opening the CGM+ app on the watch** caused one extra immediate request (01:03:23). Otherwise the cadence is the
  5-minute Connect IQ background floor.
- **The CGM+ Datafield made no request of its own** during a 6-minute activity, yet showed the value. It reads CGM+
  GB's data on the watch.
- Responses are ~5.6 KB (72 brief entries). That is comfortably within the watch's limits, but it is why apps use
  `brief_mode`.

## What the server answers

[`XdripServer.kt`](../poc/spikes/glucobridge-android/app/src/main/kotlin/dev/spike/glucobridge/XdripServer.kt), ~250
lines, no dependencies. It copies Juggluco's output field for field ([captured samples](evidence/08-garmin/glucobridge-responses.txt)):

```json
[{"date":1792102244000,"sgv":75,"delta":-0.250,"direction":"Flat","noise":1,"units_hint":"mgdl","sensor_status":"03-10-26 18:03 (12d 7h)"},
{"date":1792101649000,"sgv":72,"delta":-1.300,"direction":"Flat","noise":1}, …]
```

- `sgv` is always mg/dL; the watch converts. `delta` is mg/dL **per 5 min**. For live readings it is the sensor's
  rate × 5, which is what Juggluco reports; for 5-minute history points it is the slope to the previous point.
  `direction` uses the Nightscout names (±1/2/3 mg/dL/min).
- Newest first, spaced ≥ `interval` − 30 s apart (default 270 s, Juggluco's). Without the slack, Libre's ±1 s
  jitter turns a 300 s request into 6-minute steps.
- Without `brief_mode`, the full xDrip entry is sent (`_id`, `device`, `dateString`, `sysTime`, `filtered`,
  `unfiltered`, `rssi`, `type`). `units_hint` goes on the first entry, plus `sensor_status` when `sensor=Y`.
- Also: `/api/v1/entries/sgv.json`, `/api/v1/entries.json`, `/pebble`, `/status.json`, `HEAD`. Unknown paths → 404.
  `api-secret` is not checked: loopback only.
- **History:** the sensor backfill on connect now covers 24 h, the span of CGM+'s longest graph (was 3 h). The
  1-minute live readings are kept in `files/readings.csv`. The sensor's stored history has a 5-minute step and lags
  ~16 min, so without persistence every service restart left a 10–15 min hole in the watch graphs (seen during the
  test: the user saw it in CGM+'s 1 h graph).
- **Power:** one thread blocked in `accept()`. There is no polling and no wakelock. The work is ~3 requests per
  5 min, each a few ms. Not measured separately (the phone was needed for investigation 11's battery runs).
- **Only in the `direct` flavor.** In the `juggluco` flavor, Juggluco's own server must keep the port; whoever
  binds first wins, and that could silently starve the watch.

## Findings worth keeping

1. **The only integration surface is HTTP on `127.0.0.1:17580` through Garmin Connect.** It's simple and robust, but
   the watch polls every 5 min, so the watch lags the 1-minute sensor by up to 5 min (plus the apps' own offsets).
   Push needs our own watch app (next spike).
2. **Copy Juggluco's dialect, not xDrip's.** The user's apps are set to "Juggluco" and send Juggluco-only
   parameters (`interval`). An xDrip-shaped server would ignore them and still mostly work, but would return the
   wrong spacing or count.
3. **Juggluco's backfilled entries have `"direction":""`** (seen right after it reconnected). Ours always carry a
   direction or `"NONE"`.
4. **Port 17580 is a shared resource.** Other local apps poll it too. Our own GlucoStatus (05) and CGM Nostr (03)
   use Juggluco's `/api/v1/entries.json?interval=0` and its SSE stream `/x/live`. Our server returns 404 for
   `/x/live`, so GlucoStatus fell back to polling every 5 s and CGM Nostr reported "Juggluco unreachable". The real
   app should serve all of its own consumers, or implement `/x/live`.
5. **Garmin Connect is the single point of failure.** It must stay alive and unrestricted in the background. CGM
   Gauge's developer reports Garmin Connect blocking HTTP after a while (since July 2025), fixed by a sync in
   Garmin Connect. We didn't see it in 30 min.

## For the next spike (own watchface / app / datafield)

- **Keep the HTTP server** anyway. It makes every existing Garmin CGM app work, so users don't have to choose.
- **Push instead of poll:** the Connect IQ *Mobile SDK* (`ciq-companion-app-sdk`) lets the phone app send messages to
  our own watch app through Garmin Connect. AAPS (to its Glucose* apps) and Juggluco (to Kerfstok) do this. Juggluco
  even ships an experimental **direct BLE path to the watch that bypasses Garmin Connect**
  (`DirectGarminIQ.java`, Garmin's `6a4e8022-…` service), which is worth studying.
- **Publish complications** from our watch app with the long labels CGM+ GB uses (`CGM-GB-value` / `CGM-GB-data` /
  `CGM-GB-graph`, documented at [watchface.io/docs/cgm/developer](https://watchface.io/docs/cgm/developer)), and
  short label `CGM`. Then the user's Instinct3 N face (fields "CGM+ GB" / "CGM (Other)") can show our data even before
  we have our own face.
- **Also explore root modules**, as in [05](05-android-status-bar.md) (KernelSU + LSPosed with libxposed API 102,
  scope = Garmin Connect). These are ideas to test, not verified:
  - **Coexistence without owning the port.** Use an iptables `-m owner --uid-owner <Garmin Connect>` redirect of
    `127.0.0.1:17580` to our own port, or a hook on Garmin Connect's HTTP proxy. The watch then reads us while
    Juggluco/GDH keep their server, so the port-17580 conflict in finding 4 goes away.
  - **Answer `makeWebRequest` inside Garmin Connect.** Hook the proxy and return our JSON directly, with no socket
    and no HTTP-only/HTTPS rules. Also check whether the "HTTP blocked after a while" bug can be patched there.
  - **Faster than 5 min.** The 5-minute floor is enforced on the watch, so a hook can't lift it. But a hook could
    push a phone-app message the moment a reading lands, to wake our watch app (also possible rootless via the
    Mobile SDK; compare both).
  - **Use Garmin Connect's own BLE link to the watch** instead of Juggluco's approach of opening a second GATT
    client (`DirectGarminIQ.java`). Removes the dependency on the public SDK's behaviour.
  - Apply 05's safety rules: hooks on a big third-party app, crash loops, and an easy disarm switch.
- Store-listing permissions and changelogs are readable without a login at
  `https://apps.garmin.com/api/appsLibraryExternalServices/api/asw/apps/<uuid>`.

## Open questions

- [ ] Battery cost of the server under real polling. Expected to be negligible; measure in a future 06-style run.
- [ ] Do the apps behave differently in "xDrip+" mode? Not tried: no settings were changed, on purpose.
- [ ] `/x/live` (Juggluco SSE) and `/api/v1/treatments.json`, if any consumer needs them.
- [ ] The CGM+ app's "Request Key" setting presumably maps to `?key=` / `api-secret`. Unused here.

## Reproducing

```sh
cd poc/spikes/glucobridge-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home PATH="$HOME/.local/bin:$PATH"
./gradlew testDirectDebugUnitTest assembleDirectDebug        # XdripServerTest replays the watch's queries
adb install -r -g app/build/outputs/apk/direct/debug/app-direct-debug.apk
adb shell am force-stop tk.glucodata                         # frees the sensor *and* port 17580
adb shell am start -n dev.spike.glucobridge/.MainActivity
adb logcat -s GlucoBridge.Xdrip                              # one line per watch request
adb shell run-as dev.spike.glucobridge cat files/xdrip-requests.log
```
Watch side: CGM+ BG app → "CGM System" = Juggluco (or xDrip+), "Allow Access from Other Apps" on; face field =
"CGM+ GB". To go back: relaunch Juggluco, then reinstall the `juggluco` flavor if you used it before.

Sources: [watchface.io CGM docs](https://watchface.io/docs/cgm/intro) ([sources](https://watchface.io/docs/cgm/xdrip-installation),
[settings](https://watchface.io/docs/cgm/settings), [developer](https://watchface.io/docs/cgm/developer)),
[CGM Connect docs](https://cgmconnect.exparrot.de/), [xDrip local web service](https://github.com/NightscoutFoundation/xDrip/blob/master/Documentation/technical/Local_Web_Services.md),
Juggluco `watchserver.cpp`, [AAPS GarminPlugin](https://github.com/nightscout/AndroidAPS/blob/master/plugins/sync/src/main/kotlin/app/aaps/plugins/sync/garmin/GarminPlugin.kt),
[Garmin `Communications` API](https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications.html).
