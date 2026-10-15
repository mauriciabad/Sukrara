# 03 — Nostr as the backbone of a no-hosting Nightscout alternative

*2026-10-15 · investigation report · code in [`spikes/nostr/`](../spikes/nostr)*

## Goal

Bluetooth (PoC 1/2) covers "nearby, no network". The bigger idea: a **Nightscout alternative that needs no
self-hosting and no technical knowledge** — install the apps, scan a QR code, done — that still works with the
**Nightscout ecosystem** (Home Assistant, xDrip+, Nightguard, LoopFollow, bedside displays, watchfaces), can be read
**from anywhere**, and can be **shared** (e.g. parents following a kid). Requirements from the user:

- a reading every minute; any device can see the **last 12 h**; devices may keep the **full history** locally
- don't spam public relays; performance and battery matter
- drop-in compatible with Nightscout clients

**Question: does Nostr fit?**

## Answer

**Yes, as the transport/identity layer — with Nightscout-compatible adapters at the edges.** Proven today:

| Claim | Evidence |
|---|---|
| Public relays carry an E2E-encrypted 1-min stream with ~1 s latency | publisher → 6 public relays → follower: **1.1–1.2 s** (whole-second timestamps) |
| No relay spam: relays store **24 events/day** per user, self-expiring | hourly *addressable* chunks (replaced in place, 7-day NIP-40 expiry) + live events with 1 h expiry |
| A new device gets the last 12 h in seconds | follower backfilled **12 h in 8 s** from hourly chunks |
| Stock Nightscout clients work unmodified | **Home Assistant 2026.10.1's built-in `nightscout` integration** set up against our adapter → `sensor.blood_sugar = 158 mg/dL` with delta/direction; 17/17 request-shape checks for HA, xDrip+, Nightguard, LoopFollow, M5 NightscoutMon, nightscout-clock, Garmin |
| Key handling works with password managers | NIP-49 `ncryptsec` export/import round-trips |
| Android publisher | *see [Android](#android-publisher)* |
| Mac follower (native) | *see [Mac](#mac-follower)* |

Main risks found: **relay policies** (web-of-trust filters reject new keys, rate-limit bans), **mobile data volume**
(~5.5 MB/day upload with 4 relays), and the pieces Nostr can't do (iOS background alarms, public HTTPS for
some clients, "no data" alarms).

## Architecture

```
                         ┌──────────── public Nostr relays (pipe + 7-day buffer) ────────────┐
sensor ─BLE─▶ phone app ─┤ live: kind 21078, 1/min, encrypted, expires 1 h                   ├─▶ followers
              (uploader) │ history: kind 30078, 1 per hour, replaced every 5 min, expires 7 d│   (local DB each)
                         └───────────────────────────────────────────────────────────────────┘
followers: Mac menubar · Android/iOS follower apps · Home Assistant add-on · …
each follower can expose the Nightscout v1 API (localhost / LAN) → existing Nightscout clients plug in
```

Spec: [`spikes/nostr/PROTOCOL.md`](../spikes/nostr/PROTOCOL.md). Key points:

- **Two keys.** The uploader's *source key* signs; a shared *read key* is the NIP-44 v2 encryption target, so one
  event serves any number of followers. Followers get `npub(source)` + `nsec(read)` + relays as a link / QR
  (`cgmnostr:1?s=…&k=…&r=…`). Revocation = rotate the read key.
- **Chunk ids are opaque** (`sha256("cgm1:" + readKeyPub + ":" + hour)`), the read key's pubkey is never published:
  relays see an anonymous key posting small blobs, nothing that says "glucose".
- **Devices are the database, relays are a buffer.** Each follower upserts into its own SQLite (all-time history);
  relays hold ≤ 7 days.

## Relay survey (2026-10-15)

Probe: [`relay_survey.py`](../spikes/nostr/relay_survey.py), raw: [`results/`](../spikes/nostr/results).

| Relay | Live (ephemeral) delivery | Chunk stored + replaced | 10-event burst | Stores "ephemeral" kinds | NIP-40 expiry honored |
|---|---|---|---|---|---|
| nos.lol | ✅ 0.12 s | ✅ | 10/10 | yes | – |
| relay.primal.net | ✅ 0.19 s | ✅ | 10/10 | yes | – |
| relay.damus.io | ✅ 0.31 s | ✅ | **3/10, then IP ban** | yes | – |
| nostr.mom | ✅ 0.12 s | ✅ | 10/10 | yes | ✅ |
| offchain.pub | ✅ 0.18 s | ✅ | 10/10 | yes | ✅ — **later rejected a new key: "not in our web of trust"** |
| nostr.oxtr.dev | ✅ 0.14 s | ✅ | 10/10 | yes | ✅ |
| relay.nostr.net | ✅ 0.08 s | ✅ | 10/10 | yes | ✅ |
| relay.snort.social | ✅ 0.08 s | ✅ | 10/10 | no | ❌ served after expiry |
| nostr-pub.wellorder.net | ✅ 0.20 s | ✅ | 10/10 | no | ✅ |
| nostr.wine | ❌ paid only | | | | |
| relay.nostr.band, relay.nostr.bg | ❌ down / DNS | | | | |

Lessons:
1. **"Ephemeral" isn't ephemeral on most relays** (8/9 stored kind 21078). Always add a short `expiration` tag.
2. **Never burst.** Damus rate-limited after 3 events and then **banned the whole home IP** for a while (the phone on
   home Wi-Fi shares it). Clients must pace (≥ 2 s per relay) and back off on `rate-limit`/`banned` replies.
3. **Web-of-trust filters** reject fresh, unknown keys (offchain.pub flipped from accept to reject within an hour).
   A product that generates a key per user must not depend on any single public relay; options: let the app pick
   from a tested default set, offer a paid relay, or ship a tiny relay for the Home Assistant add-on.
4. Retention beyond a few hours was **not measured** — re-check the 7-day buffer before relying on it.

## Data volume

Measured with [`sizes.py`](../spikes/nostr/sizes.py): live event **574 B**, hourly chunk **1.9 KB** (60 readings).

| | per relay | 4 relays |
|---|---|---|
| uploader, per day | 1.4 MB | **5.5 MB** (~165 MB/month) |
| stored on relay (steady state) | 319 KB | |
| follower download, per day | 0.8 MB × relays subscribed | |

~260 B of each event is fixed Nostr overhead (id, pubkey, signature), so the levers are **fewer relays** (3 is
plenty for writes, 2 for live reads) and **republishing chunks less often** (every 15 min → chunks drop to 0.18 MB/day
per relay), not payload compression.

## Nightscout compatibility

A research pass read the source of the main clients to find the API surface they really use (full table in the
[appendix](#appendix-nightscout-api-surface-by-client)). The follower implements **tier 1** (read-only):
`/api/v1/status(.json)`, `/api/v1/entries(.json)`, `/entries/sgv(.json)`, `/entries/current(.json)` with
`count` + `find[date|dateString][$gt|$gte|$lt|$lte]` + `find[type]`, empty `treatments`/`devicestatus`/`profile(s)`,
`/api/v2/properties`, `/sgv.json` (xDrip local-server style), `/pebble`, and returns **404 for `/api/v3/*`** (Nightguard
then falls back to v1). Gotchas baked in: lists newest-first, `date` as integer ms, `delta` never null (py-nightscout
crashes), numeric `trend` matching `direction`, 24-hex `_id`, mount at `/`.

[`api_check.py`](../spikes/nostr/api_check.py) replays each client's request shapes — **17/17 pass**.
[`ha_check.py`](../spikes/nostr/ha_check.py) runs **stock Home Assistant** in Docker, onboards it through its API
and adds the built-in Nightscout integration via the normal config flow:

```
onboarded HA 2026.10.1
config flow form: ['url', 'api_key']
config flow result: create_entry cgmnostr None
sensor.blood_sugar = 158 mg/dL  attrs={"device": "synthetic", "delta": -2.9, "direction": "Flat",
                                       "device_class": "blood_glucose_concentration", "icon": "mdi:arrow-right"}
```

From there the user's bedroom-light idea is a standard automation (not executed in this PoC):
```yaml
automation:
  - alias: High glucose at night → bedroom light
    triggers: [{trigger: numeric_state, entity_id: sensor.blood_sugar, above: 180, for: "00:10:00"}]
    conditions: [{condition: time, after: "23:00:00", before: "07:00:00"}]
    actions: [{action: light.turn_on, target: {entity_id: light.bedroom}, data: {color_name: red, brightness_pct: 30}}]
```

Where the adapter runs decides which clients can reach it:

| Adapter location | Reachable by | Notes |
|---|---|---|
| on the phone, `127.0.0.1` | apps on that phone (xDrip+, AAPS-local, **Garmin via the phone** — Connect IQ allows `http://127.0.0.1`) | serverless |
| Mac app / **Home Assistant add-on**, LAN | HA itself, xDrip+, Nightguard, LoopFollow, ESP32 displays (all accept plain HTTP) | serverless; HA add-on doubles as an always-on follower |
| public HTTPS gateway | iOS apps away from home, cloud services, cellular watches, **AAPS (hardcodes HTTPS)** | needs a server holding a (revocable) read key — the one hosted piece |

## Android publisher

*TODO — filled from the Android agent's measurements.*

## Mac follower

GlucoseBar got a third transport, [`NostrSource.swift`](../spikes/menubar/Sources/GlucoseBar/NostrSource.swift)
(`swift run GlucoseBar -nostr-file <link file>`, or `defaults write dev.spike.GlucoseBar nostr 'cgmnostr:1?…'`):
one `URLSessionWebSocketTask` per relay, REQ for the 12 hourly chunks + live events, dedupe by event id *before*
crypto, signature check, NIP-44 decrypt, merge into the existing store. Crypto: rust-nostr's
[`nostr-sdk-swift`](https://github.com/rust-nostr/nostr-sdk-swift) 0.45.1 — builds with Command Line Tools only.

Measured over 2 × 10 min (release build, Nostr only), values identical to the Python follower every minute:

| | |
|---|---|
| Latency (reading → menubar) | **0.2–0.5 s** (typ. 0.3 s) |
| CPU | 0.56 s at launch (12 h backfill, ~23 chunk decrypts); **~1.3 s/hour** steady (0.035 %) |
| Idle wakeups | ~0/min (`top`), 4 threads |
| Memory | 44–58 MB RSS (~+10 MB vs. no Nostr) |
| Download | ~135 KB/h ≈ **3.2 MB/day** with 6 relays subscribed (each delivers its own copy of a live event) |
| Decrypt + verify | 0.2–0.8 ms/event |

Side findings:
- GlucoseBar's own **15 s UI refresh timer** was the biggest steady-state CPU cost; replacing it with a one-shot
  "becomes stale" timer (+ a ticker only while the menu is open) cut CPU ~4×.
- A 2 s latency bump every 5 min came from the **publisher** pacing a chunk republish ahead of the live event →
  live events now jump the queue.
- rust-nostr costs **build size**: binary 342 KB → 13.4 MB, a 215 MB static lib, 25–50 s incremental links. A
  CryptoKit + small secp256k1 package would be ~1 MB if that matters for the real app.
- Plain-text `nsec` in UserDefaults → Keychain in the real app. Dead relays (bans/policies) should be dropped after a
  while instead of being pinged forever.

## What Nostr doesn't solve

- **iOS background alarms** need APNs → a push gateway owned by the app developer (and Apple's Critical Alerts
  entitlement for night alarms). ntfy was considered and **parked**: it can't wake a third-party iOS app.
- **"No data" alarms** must come from something that isn't the uploader (it may be the thing that died): the HA add-on
  or follower apps.
- **Uploading into the system from existing apps** (xDrip+/Juggluco/AAPS → "Nightscout URL") needs tier 2 (`POST
  /api/v1/entries`, treatments) on a phone-local adapter — not built yet.
- **Treatments, profiles, devicestatus, looping (AAPS/Loop/Trio)** — out of scope; AAPS also needs HTTPS + API v3.

## Decisions

1. Use Nostr as the sync/identity/sharing layer: kind 21078 live (1 h expiry) + kind 30078 hourly chunks (7 d expiry),
   NIP-44 to a shared read key, NIP-49 for key backup, QR/link onboarding.
2. Every follower keeps the full history locally; relays are a short buffer.
3. Nightscout compatibility via adapters at the edges, starting with read-only tier 1 (validated against HA).
4. Relay set: ship a tested default (nos.lol, relay.primal.net, relay.nostr.net, nostr.mom …), pace writes, back off on
   policy/rate-limit replies, and plan for a paid or self-run relay option.

## Open questions / next PoCs

- [ ] 24 h+ soak on the phone (screen off, mobile data): battery, data, relay bans, 7-day retention check.
- [ ] Home Assistant add-on = follower + Nightscout API + "no data" watchdog (+ optional private relay).
- [ ] Read-key rotation / revoking a follower; multiple followers with different permissions.
- [ ] Tier 2 upload endpoint on the phone so xDrip+/Juggluco/AAPS can feed it.
- [ ] iOS follower + push gateway; Critical Alerts entitlement.
- [ ] Merge the publisher into GlucoBridge's outbound seam (`BridgeService.sink.onReading`, see [02](02-direct-sensor.md)).

## Reproduce

```sh
cd spikes/nostr
uv run relay_survey.py --expiry-wait                     # relay survey
uv run keys.py new --password '…'                        # test identity → secrets/ (gitignored)
uv run publisher.py --synthetic                          # or --juggluco http://127.0.0.1:17580
uv run follower.py --link-file secrets/follower-link-desktop.txt --port 1337
uv run api_check.py http://127.0.0.1:1337                 # client request-shape checks
docker run -d --name ha-poc3 -p 8123:8123 -v /tmp/ha-poc3:/config ghcr.io/home-assistant/home-assistant:stable
uv run ha_check.py                                        # stock HA + built-in Nightscout integration
```

## Appendix: Nightscout API surface by client

From the clients' source code (2026-10-15 HEADs):

| Client | Calls | Auth | Must-haves |
|---|---|---|---|
| Home Assistant (`nightscout`, py-nightscout 1.2.2) | `status.json`, `entries/sgv.json` (no params, uses `[0]`), every 1 min | optional `api-secret` sha1 | `settings` object; entry `sgv delta direction device dateString`; `delta` non-null |
| xDrip+ follower | `entries.json?count=10` then `count=2880&find[date][$gt]=`; `treatments`; `devicestatus.json?count=1` | `api-secret` sha1 | root mount; 4xx on devicestatus disables it |
| Nightguard (iOS) | `GET /api/v3/version` first (**404 ⇒ v1**), `entries.json` windows, `status.json` units, `devicestatus`, `treatments`, `/api/v2/properties` | `?token=` (v1) | `settings.units` required |
| LoopFollow (iOS) | `status.json`, `entries.json?find[date][$gte]&find[type][$ne]=cal`, `profiles.json`, `treatments.json` | `?token=` | `date` ms, newest first, root mount |
| M5 NightscoutMon | `entries.json?count=10` every 15 s, `/api/v2/properties/iob,cob,delta…` | `?token=` | `date` integer ms; delta from properties |
| nightscout-clock | `entries?find[date][$gt]&$lte&count=30` (Accept: json) or `/sgv.json?brief_mode=Y` | `api-secret` | numeric `trend` preferred |
| Garmin watchfaces | `entries/sgv.json?count=11` (HTTPS) or local `http://127.0.0.1:17580/sgv.json` | `?token=` | public-CA HTTPS unless localhost |
| AAPS NSClient v3 | full API v3 + JWT + Socket.IO | Bearer | **HTTPS only** — tier 3, out of scope |
