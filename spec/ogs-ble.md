# OGS-B — Open Glucose Stream over Bluetooth LE

`draft 1` · wire version 2 · 2026-10-18 · [index](README.md) · events: [ogs-nostr.md](ogs-nostr.md)

A follower near the owner's phone can receive the stream over Bluetooth Low Energy, with no internet and no shared
network. The link carries **the same signed, encrypted OGS events** as the relays, so it adds no new trust: a
follower checks a Bluetooth event exactly like a relay event (ogs-nostr N§9). Nearby followers can also join without
a QR code, and writers can send treatments.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY and OPTIONAL are to be
interpreted as described in RFC 2119 and RFC 8174 when, and only when, they appear in all capitals.

## B§1 Roles

| | GAP | GATT | does |
|---|---|---|---|
| **owner** (the phone with the sensor) | peripheral, advertising | server | answers `HELLO`, sends its backlog, pushes every event it publishes, receives requests and treatments |
| **follower** | central | client | scans, connects, subscribes, sends `HELLO`, requests and treatments |

One owner serves any number of followers at once. A follower talks to one owner at a time.

## B§2 GATT service

| | UUID | Properties |
|---|---|---|
| service | `7c6a0001-5c3e-4f0a-9a57-c95e0b1e0007` | primary |
| RX (follower → owner) | `7c6a0002-5c3e-4f0a-9a57-c95e0b1e0007` | write with response |
| TX (owner → follower) | `7c6a0003-5c3e-4f0a-9a57-c95e0b1e0007` | notify (CCCD `0x2902`) |

The UUIDs are random 128-bit values, not Bluetooth SIG assigned numbers, and no vendor's.

## B§3 Advertising

- The owner advertises **connectable**, with the 128-bit service UUID in the advertising data. It SHOULD use a
  resolvable private address, which is the Android default.
- Draft 1: the scan response carries the phone's Bluetooth name (on Android, the name the user gave the phone).
  **[P12]** Draft 2 sends no name: the follower shows the owner's profile from `INFO` instead.
- The owner SHOULD advertise only while it has at least one follower, or while nearby pairing is open.
- Followers scan **by service UUID**. They MUST NOT reconnect by a stored address alone, because the private address
  changes when the owner app restarts.

## B§4 Framing

### B§4.1 Frame

Every GATT write (RX) and every notification (TX) is one frame:

```
offset 0      2      4        6
       msgId  idx    count    payload…          (big-endian unsigned 16-bit integers)
```

- `msgId` numbers messages per sender. It starts anywhere and wraps from 65535 to 0.
- `idx` is 0 … `count−1`. Frames go out in order.
- Payload bytes per frame = `min(ATT_MTU − 3, 512) − 6`, and at least 14 (the size at MTU 23). The cap of 512 is the
  ATT attribute limit: a 514-byte notification fails on Android even at MTU 517.
- An empty message is still one frame (`count` = 1).

### B§4.2 Flow control

- The owner sends **one notification at a time per follower**, waiting for the stack's sent callback
  (`onNotificationSent` on Android) before the next.
- The follower sends **one write at a time**, waiting for the write response.
- The follower SHOULD request ATT MTU 517. The owner uses each follower's negotiated MTU, which is 23 until the
  follower negotiates.

### B§4.3 Reassembly

The receiver keeps one buffer per `msgId`. A frame with `idx` = 0, or with a different `count`, starts that buffer
again. When all `count` parts are there, it joins the payloads and decodes them as **UTF-8 JSON**. The result is one
message (B§5). Frames shorter than 6 bytes, or with `idx ≥ count` or `count = 0`, are dropped.

**[P13]** Draft 1 apps set no limits. A receiver MUST drop a buffer that has been idle for 30 s, MUST drop a message
over 256 KiB, and SHOULD keep at most 16 open buffers.

Payloads are not compressed. They are mostly NIP-44 ciphertext, which doesn't compress.

## B§5 Messages

Each message is a JSON array in the Nostr wire style:

| Message | Direction | Meaning |
|---|---|---|
| `["HELLO", {"v":2}]` | follower → owner | "send `INFO`, then everything you have". Sent after notifications are enabled, and again after accepting a key with a new `gk`. |
| `["INFO", {"v":2,"i":"<I hex>","relays":["wss://…"],"pair":null}]` | owner → follower | the answer to `HELLO`. `pair` is non-null only while nearby pairing is open: `{"t":"<token>","x":<expiry>,"profile":{…}}`. |
| `["EVENT", <signed event>]` | both | any OGS event: live (21078), alert (21079), chunk, roster or treatment (30078), and gift wraps (1059: key deliveries, requests, rekeys and the rest) |

Receivers MUST ignore message types they don't know. **[P8]** Draft 2 adds `"caps"` to `HELLO` and `INFO`.

## B§6 Session

```
follower                                              owner
  scan(service UUID) → connect → MTU 517 → discover → CCCD on TX
  ── HELLO ─────────────────────────────────────────▶
  ◀──────────────────────────────────────────── INFO
  ◀── EVENT roster, EVENT chunks, sensor, alert, treatments, live, recent wraps (the backlog)
  ◀── EVENT … every event the owner publishes from now on (live about once a minute)
  ── EVENT request / rekey / history / treatment ───▶  owner applies ogs-nostr N§9.2
```

- **Backlog.** On `HELLO`, the owner sends what it has cached:
  - the newest roster;
  - the hour chunks of the last ~38 hours;
  - the newest sensor update and the newest alert;
  - the treatments of the last 30 days (the newest 200);
  - the newest live event;
  - the last 10 gift wraps, so a follower that dropped out during approval still gets its key.

  The **owner backup and the invite wrap MUST NOT be sent over Bluetooth**. Followers MUST NOT depend on the order.
  **[P13]** The owner MUST NOT send cached events whose expiration has passed.
- **Push.** After the backlog, the owner sends every event it publishes to every subscribed follower. When it
  receives a treatment over Bluetooth and accepts it, it forwards the event to its other followers.
- **Both transports.** A follower SHOULD keep its relay subscriptions while connected over Bluetooth. Copies are
  deduplicated by event `id`, and the first to arrive wins.
- **Which owner.** A follower that already follows a stream MUST check that `INFO.i` is its stream's `I` before it
  sends anything but `HELLO`. If not, it MUST disconnect and keep scanning. **[P12]** The Android reference reconnects
  to the first owner it finds without this check. Events are verified anyway, but requests and treatments could go
  to the wrong phone.

## B§7 Nearby pairing

1. The wearer opens "pair nearby". The owner creates an ordinary invite token (single use, expiring in 10 minutes)
   and puts it in `INFO.pair`, together with the owner's profile.
2. A follower that follows nobody connects, reads `INFO`, and shows the owner's profile. If the user picks it, the
   follower sends an access request (ogs-nostr N§7.4) as an `EVENT` over RX, and shows the match code.
3. Every phone in range can see the token. So the owner MUST make the wearer **type the follower's match code**
   (ogs-nostr N§7.5) before allowing. Comparing two codes on screen isn't enough.
4. The key delivery comes back as an `EVENT`. The follower sends `HELLO` again, and now it can read the backlog.

The single-claim rule and `invite-used` apply as for QR invites.

## B§8 Security and privacy

- **No pairing or bonding.** Anyone in range can connect. All trust comes from the events: signatures, the roster,
  NIP-44 to `G`, and the gift wrap's seal signer.
- **What a stranger in range learns** in draft 1: `I` (from `INFO`), the activity rhythm and sizes of encrypted
  events, and the phone's Bluetooth name. **[P12]** In draft 2:
  - `INFO.i` appears only while nearby pairing is open;
  - otherwise `INFO` carries `"m": H(sid + ":" + nonce)` and `"nonce"` (16 random bytes, new on every connection),
    which only followers holding `sid` can match;
  - there is no name in the scan response.
- **Abuse limits [P13].** Draft 1 owners take writes from anyone and answer `GATT_SUCCESS` before parsing. An owner
  SHOULD:
  - rate-limit each central (for example 20 messages a minute before it has sent a valid follower event);
  - disconnect a central that sends frames that don't parse;
  - cap the number of centrals at once (for example 8).

## B§9 Test vectors

`test-vectors/ogs-draft1.json` → `ble`: one `["EVENT", live]` message split with `msgId` 7 at ATT MTU 23, 185 and
517, with the exact frames.

## Annex A — Why not NIP-BE

NIP-BE ("Nostr BLE Communications Protocol", marked `unrecommended`) also carries Nostr messages over GATT. OGS-B
doesn't use it:
- NIP-BE advertises service UUID `0000180f-…`, which is the SIG **Battery Service**, and uses placeholder
  characteristic UUIDs (`12345678-…`, `87654321-…`).
- It picks roles by comparing device UUIDs. OGS has fixed roles: the owner is always the server.
- It DEFLATE-compresses messages. OGS payloads are ciphertext and don't compress.
- Its chunk header (1-byte index, last-byte flag) caps messages at 256 chunks, and the spec's own example disagrees
  with it.
- It syncs with NIP-77 negentropy in half duplex. OGS pushes a bounded backlog, then live events, which suits one
  writer with many readers.

The OGS framing could be offered back to NIP-BE as feedback.

## Annex B — Bluetooth SIG Continuous Glucose Monitoring Service (CGMS, 0x181F)

### B.1 Could OGS-B just be CGMS?

The SIG's CGM Profile defines a sensor (GATT server) and a collector (client). The CGM Measurement characteristic
(0x2AA7) carries the glucose (SFLOAT, mg/dL), minutes since the session started, status bits and the trend. A Record
Access Control Point (0x2A52) fetches history, and a Specific Ops Control Point (0x2AAC) runs the session
(calibration, alert levels, start and stop). The main open-source collector is xDrip+'s **GluPro** collector
(`libglupro`, built on Nordic's Android-BLE-Common-Library). It bonds, enables notifications and indications, reads
CGM Status, backfills with RACP "≥ time offset", and sets the communication interval.

CGMS doesn't fit as the sharing protocol:

| Need | CGMS |
|---|---|
| consent per follower, revocation | only the bond: whoever bonds reads everything, and unbonding is the only way to revoke |
| end-to-end encryption, the same data over relays | link-layer encryption only; nothing to send over the internet |
| identity of the wearer, profiles | none. The collector trusts whatever device it bonded to. |
| several followers | possible (feature bit "multiple bond"), but collectors expect to own the session (calibrate, start and stop) |
| treatments (insulin, carbs, notes) | none. The Glucose Service's Measurement Context (0x2A34) has carbs and medication, but only tied to fingerstick records. |
| alerts as events | partly: annunciation bits say "below patient low / hypo / above hyper / rate exceeded", but carry no episode, no repeat count, no clear |
| sensor end, warm-up | session start and run time, yes; warm-up, no (no bit) |
| history | yes, RACP, but in uint16 minutes since the session started (45 days at most) |
| units | mg/dL SFLOAT, the same as OGS |

**Verdict.** OGS keeps its own service for sharing. CGMS is worth reusing as an **optional, read-only bridge**:
local gear that already speaks CGMS (xDrip+ GluPro, and potentially watches) can show the stream without learning
OGS. **[P14]** proposes it for a later integration step. It isn't built in any app yet. The encoder and vectors are
in `reference/ogs/cgms.py`.

### B.2 Bridge rules (proposal)

- It's off by default. The wearer, or the follower whose device runs it, turns it on per collector. Bonding is
  REQUIRED, with LE Secure Connections and numeric comparison. The bond is the consent.
- It's read-only:
  - SOCP *get communication interval* answers 1 minute;
  - *set communication interval* is accepted and ignored;
  - calibration, alert-level and session start/stop opcodes answer *Op Code not supported*.
- **Not for dosing.** A bridge relays possibly delayed data. It SHOULD NOT be offered to insulin pumps or
  automated-insulin-delivery collectors. The user interface MUST say that the data is relayed.
- It runs on either the owner phone or a follower device, from the data that device has already accepted
  (ogs-nostr N§9).

### B.3 Mapping

| CGMS | from OGS |
|---|---|
| CGM Measurement: Glucose (SFLOAT) | `sgv` (mg/dL, exponent 0) |
| Time Offset (uint16 minutes) | `(t − ss) / 60`, rounded down |
| Trend (SFLOAT mg/dL/min, flag 0x01) | `r`, rounded to 2 decimals; left out if `null` |
| first annunciation octet (flag 0x20) | `0x01` session stopped when `now ≥ se` |
| third annunciation octet (flag 0x80) | the active OGS alert: `low` 0x01 (below patient low), `high` 0x02, `very_low` 0x04 (below hypo), `very_high` and `extremely_high` 0x08 (above hyper), `falling_fast` 0x10, `rising_fast` 0x20. Plus 0x40 or 0x80 below 40 or above 500 mg/dL. |
| CGM Feature (0x2AA8) | trend supported (0x008000), patient high/low, hypo, hyper, rate alerts (0x02, 0x04, 0x08, 0x10), result high/low (0x100), multiple bond (0x2000); type = interstitial fluid (9), sample location = subcutaneous tissue (5); E2E-CRC field 0xFFFF (not supported) |
| CGM Status (0x2AA9) | time offset now, plus the three octets as above |
| Session Start Time (0x2AAA) | `ss` as local date and time, with time zone and DST |
| Session Run Time (0x2AAB) | `(se − ss) / 3600` hours |
| RACP report records `≥` time offset, report number, last | the device's stored readings for this sensor |

The octet order and bit meanings follow the Nordic parser that GluPro uses: the flag-0x20 octet holds the status bits
(session stopped, battery low, …), and the flag-0x80 octet holds the result bits (below patient low, …). Check them
against the CGMS 1.0.2 specification and a real collector before shipping. Vectors are in
`test-vectors/ogs-draft1.json` → `cgms`.
