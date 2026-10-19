# OGS3-B — Open Glucose Stream draft 3 over Bluetooth LE

`draft 3` · 2026-10-19 · [index](README.md) · core: [ogs3-core.md](ogs3-core.md)

A member near a publisher device can receive the stream over Bluetooth Low Energy, with no internet and no shared
network, and can join without a QR code. The link carries the same envelopes and gift wraps as the relays, so it
adds no trust: a member checks a Bluetooth envelope exactly like a relay one (C§10). What is new in draft 3 is that
nothing a stranger can see over the air says "glucose" or stays the same for long.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY and OPTIONAL are to be
interpreted as described in RFC 2119 and RFC 8174 when, and only when, they appear in all capitals.

## B§1 Roles

| | GAP | GATT | does |
|---|---|---|---|
| **publisher** (a device of the wearer) | peripheral, advertising | server | answers `HELLO`, sends envelopes, receives wraps and members' envelopes |
| **member** | central | client | scans, connects, subscribes, sends `HELLO`, wraps and its own envelopes |

A publisher serves several members at once. A member talks to one publisher at a time.

## B§2 What draft 3 doesn't use, and why

- **Pairing and bonding.** OS pairing adds system dialogs, breaks when a bond is lost, and authenticates only the
  link. OGS authenticates every record end to end, and the Bluetooth link adds nothing to that. LE Secure Connections
  "Just Works" has no MITM protection anyway. The nearby join (B§7) does the user-visible check (a typed six-digit
  code, like Passkey Entry) at the application layer, bound to the member's key.
- **A fixed service UUID.** Draft 1 advertised one constant UUID, so anyone in range could tell that this phone shares
  a glucose stream. That is a health inference about its owner, in public. Draft 3 derives the UUIDs from the day key.
- **Encrypted Advertising Data** (Core 5.4) and periodic advertising: no Android or iOS app API exposes them, and
  iOS doesn't let apps set arbitrary advertising payloads.
- **Bluetooth Mesh.** Phones don't support it natively. It needs provisioning, and its relaying floods at a power
  cost. The stream is one publisher and a few members within ten metres, a star, and the internet relays cover
  distance.
- **The Bluetooth SIG CGM Service** (0x181F) as the sharing protocol: it has no consent per follower, no end-to-end
  encryption, no treatments and no internet path. It remains an optional read-only bridge to local collectors
  (draft 1 ogs-ble Annex B, D§11.1).
- **NIP-BE** (Nostr over BLE, `unrecommended`): it advertises the Battery Service UUID, uses placeholder
  characteristics and DEFLATEs ciphertext (draft 1 ogs-ble Annex A).

## B§3 Discovery

### B§3.1 The day service

From the BLE key of the current day (C§5):

```
uuid(bytes16)  = bytes16 with the RFC 4122 version 4 and variant bits set, as a UUID string
service        = uuid( HMAC(ble_key, "ogs3/ble/gatt/service")[0:16] )
rx             = uuid( HMAC(ble_key, "ogs3/ble/gatt/rx")[0:16] )      member → publisher: write with response
tx             = uuid( HMAC(ble_key, "ogs3/ble/gatt/tx")[0:16] )      publisher → member: notify (CCCD 0x2902)
advertised(w)  = uuid( HMAC(ble_key, "ogs3/ble/adv/" + w)[0:16] )     w = floor(unix_time / 900), decimal
```

- The publisher registers the GATT service with the day's `service`, `rx` and `tx` UUIDs, and re-registers it at
  UTC midnight and on rotation. Connected members reconnect then.
- It advertises **connectable**, with `advertised(w)` as the only 128-bit service UUID, changing it every 15 minutes
  (900 s windows). No name, no service data, no manufacturer data, no TX power.
- A member computes, for the day keys it holds, `advertised(w)` for the current window and the previous one (for
  clock skew), and scans for exactly those. It doesn't need to connect to unrelated phones to find its wearer.
- To a stranger, the advertisement is a random-looking 128-bit UUID that changes every 15 minutes, like any of the
  many devices with vendor UUIDs. The GATT UUIDs, which a stranger sees only by connecting, are random-looking and
  change daily.

The publisher SHOULD use a resolvable private address (the default on Android and iOS). It SHOULD advertise only
while it publishes a stream, and MAY stop when no member has connected for a long time.

### B§3.2 The pairing service

While the wearer has **nearby pairing** open (at most 10 minutes), the publisher also advertises, and serves, a fixed
service:

| | UUID |
|---|---|
| pairing service | `abdb0001-9b12-494c-92fc-416bcdc4ac9a` |
| RX | `abdb0002-9b12-494c-92fc-416bcdc4ac9a` |
| TX | `abdb0003-9b12-494c-92fc-416bcdc4ac9a` |

This is the only constant identifier in the protocol, and it appears only while the wearer is pairing someone in
person. These UUIDs are random, not SIG-assigned, and not any vendor's. A joining app scans for it to list
"phones that are inviting nearby", without names.

### B§3.3 Platform notes (informative)

- **Android 12+** needs `BLUETOOTH_SCAN` (with `neverForLocation`), `BLUETOOTH_CONNECT` and `BLUETOOTH_ADVERTISE`.
  A legacy advertisement fits one 128-bit UUID and the flags.
  - Scan filters by service UUID work in the background; the filter list is updated every 15 minutes.
  - A member that is a long-lived companion (a parent's phone) MAY use CompanionDeviceManager to be woken up.
- **iOS publisher in the background:** service UUIDs move to the "overflow area", where only iOS devices scanning for
  that exact UUID see them, and advertising slows down. Android members may not find a backgrounded iOS publisher.
  Members on iOS can, because they scan for the exact UUIDs.
- **iOS member in the background:** scanning for specific UUIDs works, but the app must be running to update its
  filter every 15 minutes. Once connected it stays connected (State Restoration). Followers on iOS SHOULD rely on
  relays and push (N§9) when the link drops.
- **ATT MTU:** iOS negotiates about 185 bytes; Android up to 517. The framing handles any MTU (B§4).

## B§4 Framing

Unchanged from draft 1 (ogs-ble B§4). Every write (RX) and notification (TX) is a frame:

```
offset 0      2      4        6
       msgId  idx    count    payload…          (big-endian unsigned 16-bit integers)
```

- Payload per frame = `min(ATT_MTU − 3, 512) − 6`, at least 14 bytes.
- Frames go in order. The publisher has one notification in flight per member; the member has one write in flight.
- Reassembly: one buffer per `msgId`. A frame with `idx` 0, or with a different `count`, restarts it.
  - Drop frames shorter than 6 bytes, with `idx ≥ count`, or with `count` 0.
  - Drop a buffer idle for 30 s.
  - Drop a message over 256 KiB.
  - Keep at most 16 buffers.
- A message is UTF-8 JSON, not compressed (it is mostly ciphertext).

## B§5 Messages

Each message is a JSON array, in the Nostr wire style:

| Message | Direction | Meaning |
|---|---|---|
| `["HELLO", {"nonce":"<32 hex>","since":<unix s>}]` | member → publisher | "prove you hold today's key, then send what changed since `since`". `nonce`: 16 fresh random bytes per `HELLO`. |
| `["INFO", {"nonce":"<echo>","proof":"<32 hex>"}]` | publisher → member | `proof = hex(HMAC(ble_key, "ogs3/ble/info/" + nonce))[0:32 chars]` |
| `["INFO", {"nonce":"<echo>","invite":"ogs:…"}]` | publisher → member, pairing service only | the invite text, as in a QR code |
| `["EVENT", <event>]` | both | an envelope (kind 31078) or a gift wrap (kind 1059) |

Receivers MUST ignore message types they don't know.

## B§6 Session

```
member                                                  publisher
  scan(advertised UUIDs of the held day keys) → connect → MTU → discover(day service) → CCCD on tx
  ── HELLO {nonce, since} ─────────────────────────────▶
  ◀────────────────────────────────── INFO {nonce, proof}
  ◀── EVENT envelopes changed since `since`, then every envelope as it is published
  ── EVENT wraps (rekey, history, …) and the member's own envelopes (entries, acks) ─▶
```

- **Proof first.** A member MUST check `proof` against the day key it used to find the publisher, and MUST NOT send
  anything but `HELLO` before it verifies. A wrong proof means another device: disconnect and avoid it for 10
  minutes. Any holder of the day key can produce the proof, so it proves membership, not which device. That is
  enough, because every envelope is checked on its own (C§10).
- **Backlog.** On `HELLO`, the publisher sends, newest first:
  - the envelopes of the objects it published or updated at or after `since`;
  - the newest membership;
  - the current sensor and device state;
  - the active alerts;
  - its recent wraps to members (so a member that dropped out while being approved still gets its grant).

  Default `since`: now − 24 h. It MUST NOT send expired envelopes, and MUST NOT send the owner backup (C§13) or an
  invite wrap.
- **Push.** After the backlog, every envelope the publisher publishes goes to every subscribed member. A member's
  envelope that the publisher accepts (C§10) is forwarded to the other members.
- **Both transports.** A member SHOULD keep its relay subscriptions while connected. Copies are deduplicated by event
  `id`.

## B§7 Nearby join

1. The wearer opens "invite nearby". The administrator creates an ordinary invite (single use, 10 minutes) and the
   publisher advertises the pairing service (B§3.2).
2. The joining app lists the phones advertising it (no names). When the user picks one, it connects, sends `HELLO`,
   and reads `INFO.invite`. The publisher then sends the invite wrap as an `EVENT`, so no internet is needed.
3. The joining app opens the invite (C§8.3), shows who is inviting and what is offered, sends its join as an
   `EVENT`, and shows the **match code**.
4. Every device in range can read the invite. The administrator MUST make the wearer **type** the joiner's six-digit
   code, and MUST refuse a join whose code differs (C§8.5).
5. The grant comes back as an `EVENT`. The member disconnects from the pairing service and finds the day service.

## B§8 Limits and abuse

A publisher MUST check every write before acknowledging it, and SHOULD:
- serve at most 8 members at once;
- answer a malformed frame with an ATT error, then disconnect that member;
- limit each member to 30 messages and 256 KiB a minute;
- send at most one backlog per member every 5 seconds.

A central that finds the day service holds the day key, so it is a member. A removed member can't find the service
after the rotation (B§9).

## B§9 What a nearby observer learns

| Observer | Learns |
|---|---|
| a stranger, passive | a connectable device advertising a random-looking UUID that changes every 15 minutes, its address rotating on its own schedule (RPA), and radio activity |
| a stranger who connects | a GATT service with random-looking UUIDs (changing daily). Without the day key, `INFO` can't be produced or checked. |
| anyone, while nearby pairing is open | that this phone is inviting someone to a glucose stream, and the invite text (so the wearer's profile, after opening the invite) |
| a removed member | nothing after the rotation: the publisher re-registers and advertises under the new epoch's UUIDs at once |
| a member | its wearer's phone, when in range |

Not covered:
- Android's GAP service gives a connected central the phone's Bluetooth name, and apps can't turn that off. Users
  SHOULD give their phone a neutral name.
- The 15-minute windows and the RPA rotation aren't synchronised, so a tracker can link one window to the next across
  an address change.

## B§10 Test vectors

`test-vectors/ogs-draft3.json` → `ble`: the day's GATT UUIDs, `advertised(w)` for two windows, a `HELLO` with its
`INFO` proof, a pairing `INFO`, and one envelope split into frames at ATT MTU 23, 185 and 517.
