# CGM-over-Nostr protocol — draft v1 (PoC 3)

Goal: one uploader (the phone that owns the sensor) streams 1-minute glucose readings to any number of followers
(Mac, Home Assistant, other phones, displays) through public Nostr relays, end-to-end encrypted, **without** storing
an ever-growing pile of events on relays. Each follower keeps its own full database; relays are a pipe + a short buffer.

## Keys

| Key | Who holds it | Purpose |
|---|---|---|
| **Source key `S`** (secp256k1) | the uploader only | signs every event; `S.pub` identifies the stream |
| **Read key `G`** (secp256k1) | uploader + every follower | NIP-44 v2 encryption target: content = `nip44_encrypt(S.sec, G.pub, json)`; followers decrypt with `nip44_decrypt(G.sec, S.pub, ct)` |

- One encryption per event, regardless of follower count. Revoking a follower = rotate `G` (future: rotation event).
- `G.pub` is never published (no `p` tag), so outsiders can't tie the stream to a group.
- Owner backup: both secrets exported as NIP-49 `ncryptsec…` with a password → storable in a password manager.
- **Follower link / QR**: `cgmnostr:1?s=<npub of S>&k=<nsec of G>&r=<relay>,<relay>,…` (URL-encoded relays).

## Events

All events are signed by `S`, have NIP-44 v2 content (JSON below, encrypted to `G`), and **always carry a NIP-40
`expiration` tag** — the relay survey showed most public relays *store* events in the "ephemeral" range anyway.

### Live reading — kind `21078` (ephemeral range), one per sensor reading (~1/min)
```
tags:    [["expiration", now + 3600]]
content: {"v":1,"t":1792086000,"sgv":123,"r":-0.42,"dev":"juggluco"}
```
`t` unix seconds of the reading, `sgv` mg/dL (int), `r` rate mg/dL/min (float or null), `dev` free-form source.

### Hourly chunk — kind `30078` (addressable, NIP-78 app data), one per UTC hour
```
tags:    [["d", <chunk id>], ["expiration", hourStart + 7 days]]
content: {"v":1,"h":1792083600,"dev":"juggluco","r":[[dt,sgv,rate100],…]}
```
- `dt` = seconds since `h`; `rate100` = round(rate×100) or null. ≤ 60 entries (Libre 3: one per minute).
- **chunk id** = first 32 hex chars of `sha256("cgm1:" + G.pub_hex + ":" + h)` — followers can compute it, outsiders
  can't recognise the pattern or the hour.
- Re-published (replacing the previous version) **every 5 minutes** and at the end of the hour → ≤ 288 writes/day,
  but relays keep only **24 events/day**, each ≈ 1.7 KB, auto-deleted after 7 days.

## Publishing rules

- Publish to 3–6 relays; never burst: ≥ 2 s between events to the same relay (damus bans bursts).
- Backfill after an outage = republish the affected hourly chunks (paced), never resend individual live events.

## Follower algorithm

1. On start: compute chunk ids for the last N hours (default 12, configurable) and `REQ {"authors":[S], "kinds":[30078],
   "#d":[ids…]}` on all relays; decrypt; upsert readings into the local DB (key = `t`).
2. Subscribe `{"authors":[S], "kinds":[21078], "since": now-120}` on all relays; upsert each reading (dedupe by `t`).
3. Every few minutes, re-fetch the current hour's chunk to fill any live gaps.
4. The local DB is the source of truth for that device (all-time history accumulates locally).

## Nightscout mapping (adapter)

Reading → v1 entry: `{"_id": "<S.pub[:8]>-<t>", "type":"sgv", "sgv":sgv, "date":t*1000, "dateString":ISO8601Z,
"direction":dir(r), "delta":r*5, "device":dev, "noise":1}` with `dir`: ≥3 DoubleUp, ≥2 SingleUp, ≥1 FortyFiveUp,
> −1 Flat, > −2 FortyFiveDown, > −3 SingleDown, else DoubleDown; null rate → "NONE".
