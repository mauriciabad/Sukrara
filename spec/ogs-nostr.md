# OGS-N — Open Glucose Stream over Nostr

`draft 1` · wire version 2 · 2026-10-18 · `optional` · [index](README.md) · data model: [ogs-data.md](ogs-data.md)

A person wearing a continuous glucose monitor (the **owner**) shares a live, end-to-end encrypted glucose stream with
people they choose (the **followers**) through ordinary public Nostr relays. No account, no server and no public
profile are involved. Followers join with a one-time invite and the owner's explicit approval. The owner removes a
follower by rotating the stream's key. Followers the owner allows may also add treatments (insulin, carbs, notes).

This document is written as a NIP draft. It reuses NIP-01, NIP-40, NIP-42, NIP-44 (v2) and NIP-59, and adds no new
cryptography.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY and OPTIONAL are to be
interpreted as described in RFC 2119 and RFC 8174 when, and only when, they appear in all capitals.

> **Status.** Draft 1 describes the wire format that the reference implementation (Sukrara 0.11) speaks today, so
> that a third party can interoperate with it now. Changes worth making before anyone else ships are listed in
> [PROPOSALS.md](PROPOSALS.md) and marked **[P*n*]** where they apply. Draft 2 will be draft 1 with those proposals
> applied, without backwards compatibility.

## N§1 Overview

```
 owner phone ── live, alert (ephemeral) ───────────┐
   │  device key D signs data, NIP-44 → read key G │   public relays (and/or Bluetooth, ogs-ble.md)
   │  identity key I signs roster, key deliveries  ├──────────────────────────────▶ followers (hold G, verify D, I)
   └─ hour chunks, roster, treatments (addressable)┘
 follower ── access request / rekey / history (gift wrap to I) ──▶ owner
 owner    ── key delivery / revoked / declined (gift wrap to F) ──▶ follower
```

Each piece of data is encrypted **once**, to the read key `G`. Followers have the private half of `G`, so the
owner's cost doesn't grow with the number of followers. Tags are opaque hashes of a secret stream id. Relays and
onlookers can see *that* a stream exists and how often it publishes, but not its values, its owner's identity or its
followers.

## N§2 Keys and roles

| Key | Held by | Purpose |
|---|---|---|
| **Identity `I`** | owner (backed up, e.g. in a password manager) | the key followers trust. Signs the roster and every message to followers, seals invites. Rarely used. |
| **Device `D`** | one owner device, never backed up | signs live readings, alerts, chunks and the owner's treatment log. Replaced when the phone changes. |
| **Read key `G_n`** | owner and approved followers | the NIP-44 v2 recipient of all stream data: content = `nip44(signer.sec → G_n.pub)`. Epoch `n` ≥ 1 increases on every rotation. |
| **Stream id `sid`** | owner and approved followers | 32 random bytes as 64 lowercase hex characters. Never rotates. Used only to derive opaque tags. |
| **Follower `F`** | each follower app | identifies a follower. Receives key deliveries. Signs requests and, if allowed, treatments (it is then a *writer* `W`). |
| **Invite key** | anyone holding the invite link | derived from the invite secret (N§7.2). Only receives the invite gift wrap. |

All keys are secp256k1 (BIP-340 x-only public keys, lowercase hex on the wire).

- `I` MUST be generated for this stream, or derived one-way from a main key (N§12.1). It MUST NOT be a key the user
  publishes as, or will publish as.
- `D` MUST differ from `I`.
- A follower MUST use a fresh `F` for each stream it follows.

## N§3 Kinds and constants

| Use | Kind | Range | Signed by |
|---|---|---|---|
| live reading / sensor update | `21078` | ephemeral | `D` |
| alert | `21079` | ephemeral | `D` |
| hour chunk, roster, treatment, owner backup | `30078` | addressable (NIP-78) | `D`, `I`, `D`/`W`, `I` |
| gift wrap (every private message, the invite) | `1059` | NIP-59 | one-time key |
| rumor: follower → owner | `30500` | inside a wrap only | `F` (seal) |
| rumor: owner → follower | `30501` | inside a wrap only | `I` (seal) |
| rumor: invite | `30502` | inside a wrap only | `I` (seal) |

Draft 1 constants (one place, so a later draft can rename them):

| Name | Value |
|---|---|
| tag namespace `NS` | `cgm2` |
| invite HKDF salt | `sukrara/invite/v3` **[P2]** |
| invite HKDF info | `wrap-recipient` |
| invite link prefixes | `https://sukrara.mauri.app/join#`, `sukrara:join#` **[P2]** |

**[P1]** NIP-78 says kind 30078 is for "apps that do not care about interoperability", and that relays SHOULD serve
it only to its author after NIP-42 AUTH. A relay that follows that rule would never serve chunks, the roster or
treatments to followers. Draft 2 moves them to dedicated kinds, which are free in the registry of kinds as of
2026-10-17: `31078` chunk, `31079` roster, `31080` treatment. The owner backup is private data for its own author,
which is exactly NIP-78's case, so it stays on `30078`.

## N§4 Derived values

```
H(x)     = first 32 hex chars of sha256(UTF-8 x)                 (lowercase)
gk(G)    = first 8 hex chars of sha256(G.pub as 32 raw bytes)    names an epoch in tags
hour(t)  = t − (t mod 3600)
```

| Tag value | Formula |
|---|---|
| chunk `d` | `H(NS + ":h:" + sid + ":" + hour)` (hour in decimal) |
| chunk `c` (index) | `H(NS + ":glucose:" + sid)` |
| roster `d` | `H(NS + ":roster:" + sid)` |
| treatment `d` | `H(NS + ":tr:" + sid + ":" + id)` |
| treatment `c` (index) | `H(NS + ":treatments:" + sid)` |
| owner backup `d` | `H(NS + ":backup:" + I.pub)` |
| Bluetooth `INFO.m` | `H(NS + ":ble:" + sid + ":" + nonce)` (nonce as sent, lowercase hex; ogs-ble B§5) |

Only holders of `sid` can compute or recognise these values. `gk` changes on every rotation. It reveals that a
rotation happened and nothing else.

## N§5 Stream events

Every stream event:
- has content = NIP-44 v2 of the payload's JSON, encrypted by the signer's key to `G_n.pub`;
- carries `["gk", gk(G_n)]`, except the owner backup;
- carries a NIP-40 `["expiration", <absolute unix s>]`. Many public relays store ephemeral kinds anyway, so the
  expiration is what removes them.

| Event | Kind | Signer | Tags, in this order | Expiration | Payload |
|---|---|---|---|---|---|
| live reading | 21078 | `D` | `gk`, `expiration` | `created_at` + 1 h | [D§3](ogs-data.md#d3-reading-live) |
| sensor update | 21078 | `D` | `gk`, `expiration` | `created_at` + 1 h | [D§5](ogs-data.md#d5-sensor-life) |
| alert | 21079 | `D` | `gk`, `expiration` | `created_at` + 1 h | [D§7](ogs-data.md#d7-alerts) |
| hour chunk | 30078 | `D` | `d`, `c`, `gk`, `expiration` | hour + 7 days | [D§4](ogs-data.md#d4-hour-chunk-history) |
| roster | 30078 | `I` | `d`, `gk`, `expiration` | `created_at` + 30 days | §5.4 |
| treatment | 30078 | `D` or writer `W` | `d`, `c`, `gk`, `expiration` | `created_at` + 30 days | [D§6](ogs-data.md#d6-treatments) |
| owner backup | 30078 | `I` | `d`, `expiration` | `created_at` + 90 days | Appendix A (NIP-44 from `I` to `I` itself) |

Receivers MUST NOT depend on tag order.

### N§5.1 Live readings and sensor updates

The owner SHOULD publish each new reading as a live event when it arrives. If it batches uploads to save battery, the
chunks carry the readings; live events MAY then be skipped on relays (Sukrara sends them only over Bluetooth in that
mode). The owner MUST NOT send live events for readings older than 10 minutes.

### N§5.2 Alerts

An alert is published at once, never batched. It expires in an hour and is not republished.

### N§5.3 Hour chunks

The owner republishes the current hour's chunk as it fills, every 5 minutes or so, and at the end of the hour. That
gives about 24 stored events a day, each replacing the last. After a gap, it republishes the hours it missed. Within one hour, two versions MUST
have strictly increasing `created_at` (relays reject a same-second replacement).

### N§5.4 Roster

```json
{"v":2,"epoch":1,"devices":["<D hex>"],"writers":["<W hex>"],"relays":["wss://…"],"profile":{"name":"Alex","avatar":{…}}}
```

The roster is the stream's authority, published by `I` under the current epoch:
- `epoch`: the epoch whose `G` encrypts it. It MUST equal the epoch of its `gk`.
- `devices`: the keys allowed to publish data (draft 1 always lists exactly one).
- `writers`: follower keys allowed to publish treatments. Only active followers.
- `relays`: where the stream is published.
- `profile`: the owner's profile ([D§8](ogs-data.md#d8-profiles)).

The owner MUST republish the roster on every change, on every rotation, and at least every 7 days. Followers
MUST check the roster again whenever it changes.

### N§5.5 Treatments

- The owner publishes its **whole merged log** signed by `D`: its own entries, entries from writers it accepted,
  and tombstones. It publishes each change at once. It republishes every entry with `t` ≥ now − 30 days on a
  rotation, when a follower is approved, and at least weekly.
- A writer publishes its own entries signed by its `W`.
- The owner accepts a writer's entry (N§9.2), merges it ([D§6.2](ogs-data.md#d62-merge)) and republishes it signed by
  `D`. That republished copy, the *echo*, is how the writer learns the entry was accepted. There is no explicit
  rejection: an entry that is never echoed was not accepted.
- Pen entries (D§6 `pr`, `pen`, `pd`) are the wearer's own, created and changed only by the owner's devices. When a
  pen injection matches an entry a writer created, the owner tombstones that entry and publishes a new pen entry
  (`o` = `phone`, the writer's `n` copied), so no writer can later break its doses; only the wearer's own typed
  entries are merged in place.
- `created_at` is the publish time and MUST strictly increase per `(author, d)`. The version is `u` in the payload,
  never `created_at`.

## N§6 Private messages

Every message between a follower and the owner is a **NIP-59 gift wrap** (kind 1059). It works like this:
- the **rumor** is an unsigned event of the kind below, with empty tags and `created_at` = now;
- the **seal** (kind 13) is signed by the sender and carries the rumor, NIP-44 encrypted to the recipient;
- the **wrap** (kind 1059) is signed by a one-time key and tagged `["p", recipient]`.

The seal's signer is the authenticated sender. Receivers MUST check that the rumor's `pubkey` equals the seal's
signer (NIP-59).

Wraps MUST carry an **absolute** NIP-40 expiration of now + TTL. NIP-59 backdates `created_at` by up to 2 days, so
an expiration computed from `created_at` would arrive already expired. (Sukrara for Mac sent its wraps without one
until P5 was integrated.)

| Message | Rumor kind | From → to | TTL | Payload |
|---|---|---|---|---|
| access request | 30500 | `F` → `I` | 7 d | `{"v":2,"type":"request","token":t,"profile":{…}}` |
| profile update | 30500 | `F` → `I` | 7 d | `{"v":2,"type":"profile","profile":{…}}` |
| rekey request | 30500 | `F` → `I` | 7 d | `{"v":2,"type":"rekey","have":"<unknown gk>"}` |
| history request | 30500 | `F` → `I` | 1 d | `{"v":2,"type":"history","since":<hour start>}` |
| key delivery | 30501 | `I` → `F` | 30 d | `{"v":2,"type":"key","sid":sid,"epoch":n,"g":"<nsec of G_n>","devices":[…],"relays":[…]}` |
| revoked | 30501 | `I` → `F` | 30 d | `{"v":2,"type":"revoked"}` |
| invite used | 30501 | `I` → `F` | 7 d | `{"v":2,"type":"invite-used"}` |
| declined | 30501 | `I` → `F` | 7 d | `{"v":2,"type":"declined"}` |
| invite | 30502 | `I` → invite key | until expiry | N§7.3 |

The private half of `G_n` travels as a NIP-19 `nsec`. Receivers MUST ignore a message whose `type` they don't know.

## N§7 Invites and consent

### N§7.1 The invite link

```
payload = base64url_nopad( I.pub[0:8] ‖ s )        s = 16 random bytes → exactly 32 characters
link    = https://sukrara.mauri.app/join#<payload>   or   sukrara:join#<payload>       [P2]
token t = base64url_nopad(s)                        22 characters
```

The link is a QR code (version 5) or a text to share. It holds the identity's 8-byte **fingerprint** and the invite
**secret**, and nothing else. Expiry, relays and the owner's profile are inside the invite wrap. A receiver MUST
reject anything other than a known prefix followed by exactly 32 characters of `[A-Za-z0-9_-]`.

**[P2]** Draft 2 accepts the payload after any `https://<host>/join#` and after a neutral scheme `ogs:join#`, and
renames the HKDF salt to `ogs/invite/v1`. Each app can then host its own landing page. The fingerprint
authenticates the owner, not the host.

### N§7.2 Invite key

```
derive_scalar(ikm, salt, info) = the first k = HKDF-SHA256(ikm, salt, info ‖ [ctr if ctr > 0], 32 bytes)
                                 with 1 ≤ k < secp256k1 order, for ctr = 0, 1, …
invite_key(s)                  = derive_scalar(s, "sukrara/invite/v3", "wrap-recipient")
```

Anyone with the link can derive the invite key. That is the point: the follower uses it to find and open the invite.

### N§7.3 Invite wrap

The owner publishes, on the default relays (N§8.1), a gift wrap to `invite_key(s)`, sealed by `I`, with
`expiration` = the invite's expiry (at least now + 60 s):

```json
rumor kind 30502: {"v":3,"type":"invite","x":<expiry>,"r":["wss://…"],"profile":{"name":"Alex","avatar":{…}}}
```

The follower:
1. decodes the link and derives the invite key;
2. sends `REQ {"kinds":[1059],"#p":[invite_key.pub]}` to the default relays, answering NIP-42 AUTH with the invite
   key;
3. accepts a wrap only if it opens with the invite key, the rumor kind is 30502, the seal's signer **starts with the
   fingerprint** and `x` is in the future (`x > now`);
4. learns from it `I`, the relays and the owner's profile. It shows who is inviting before asking.

The fingerprint check stops anyone else who holds the link from publishing a wrap that points the follower at their
own identity. Forging 8 bytes of a secp256k1 key costs about 2⁶⁴ key generations, and the invite lasts 24 h.

### N§7.4 Request, approval, single claim

1. The follower generates `F` and sends an access request with `token = t` and its profile to `I` on the invite's
   relays. It shows the match code (N§7.5) and waits.
2. The owner accepts a request only with a known, unexpired token. The **first** valid request it *receives* claims
   the invite. Arrival order counts, never `created_at`, which can be backdated. Each later request on the same
   token gets an `invite-used` message, and the owner's approval prompt warns that another device also tried.
3. The owner MUST get an explicit decision from the human wearer (Allow or Deny). Names and pictures are
   self-asserted, and approving sends history at once, which can't be taken back.
4. **Allow:** the owner records `F`, sends a key delivery for the current epoch, and republishes the last 24 h of
   chunks and the last 30 days of treatments. **Deny:** it sends `declined`. Either way the invite is used up. It
   stays known until it expires, so late requests still get `invite-used`.
5. The follower decrypts the key delivery, fetches the roster, backfills chunks, then follows live.

A request from an `F` that is already approved and not revoked MAY be answered with a fresh key delivery, without a
token. The reference implementation does this.

### N§7.5 Match code

```
match_code(F, t) = int(sha256(UTF-8(F.pub_hex + t)), big-endian) mod 10000, as 4 digits with leading zeros
```

The follower shows the code. For QR and link invites, showing it on the owner's side is OPTIONAL. For nearby
Bluetooth pairing, where every phone in range sees the token, the owner MUST make the wearer **type** the code the
follower shows (ogs-ble §7). Typing a code beats comparing two codes in usability studies.

## N§8 Relays

### N§8.1 Relay lists

The invite and the roster carry the stream's relays. Followers SHOULD use the roster's list once they have it. The
default relays used to look up invites are part of an app's configuration. Every relay MUST:
- accept events from brand-new keys, because every wrap is signed by one;
- serve wraps to the key they are addressed to.

Measured on 2026-10-17, these four qualified: `wss://nos.lol`, `wss://relay.primal.net`, `wss://nostr.mom`,
`wss://relay.snort.social`. Relays that rate-limit by web of trust, or that serve wraps to nobody, break invites.

### N§8.2 AUTH (NIP-42)

Some relays serve `#p` subscriptions on kind 1059 only to the recipient. Clients MUST answer a challenge with a
kind 22242 event signed by the key the wraps are addressed to: `I` for the owner's inbox, `F` for a follower's,
the invite key for an invite lookup. They then re-issue their REQs. A client MUST NOT AUTH with a key that would link
the stream to the person (N§12).

### N§8.3 Follower subscriptions

| What | Filter |
|---|---|
| inbox | `{"kinds":[1059], "#p":[F], "since": now − 3 d}` (wraps are backdated up to 2 days) |
| roster | `{"kinds":[30078], "authors":[I], "#d":[roster d]}` |
| history | `{"kinds":[30078], "authors": devices, "#c":[chunk c], "since": now − 13 h}` |
| live | `{"kinds":[21078, 21079], "authors": devices, "since": now − 120}` |
| treatments | `{"kinds":[30078], "authors": devices ∪ writers, "#c":[treatment c], "since": now − 31 d}` |

Re-issue them whenever the roster's `devices` or `writers` change.

### N§8.4 Publishing

- Publish to every relay on the list. Leave at least 2 s between events to the same relay, because some relays ban
  bursts. Live events and alerts jump the queue.
- Addressable events need strictly increasing `created_at` per `(author, kind, d)`.

## N§9 Acceptance rules

These rules are the security of the protocol. A conforming receiver MUST apply them to every event, from any relay or
over Bluetooth. Where an event came from earns it no trust.

### N§9.1 Follower

Applied in this order:

1. **Integrity:** the `id` matches the NIP-01 serialisation and the BIP-340 signature verifies. Otherwise reject.
   Deduplicate by `id`.
2. **Expiration:** drop an event whose NIP-40 expiration has passed. **[P10]** Draft 1 apps rely on relays for this.
3. **Gift wraps** (kind 1059, `p` = `F`): unwrap per NIP-59. Accept only rumor kind 30501 sealed by `I`, with
   `v == 2`. Then:
   - **key:** reject it if `sid` differs from the one already known (both apps since P6). Store `G_n` under `gk(G_n)` and keep
     every epoch. If `epoch` is the newest, adopt its `devices` and its `wss://` relays. The follower becomes active
     unless revoked.
   - **revoked:** stop accepting data. The history already received stays readable.
   - **invite-used / declined:** these apply only while the follower is waiting for approval.
4. After a `revoked`, ignore all stream events.
5. **Epoch:** the event MUST carry a `gk` the follower has a key for. For an unknown `gk` on an event signed by `I`
   or a roster device, and only then, send a rekey request, at most once per `gk` per 5 minutes. The event MAY be kept and retried once the key
   arrives (relays and the Bluetooth backlog also deliver it again).
6. **Roster** (kind 30078, `d` = roster d): the author MUST be `I`; decrypt with `(G_n, I)`; `epoch` MUST equal the
   `gk`'s epoch **[P10]**. Replace the current roster only if `(epoch, created_at)` is greater.
7. **Chunk** (kind 30078, `c` = chunk c): the author MUST be in `roster.devices`. Decrypt. The `d` MUST equal the
   value derived from the payload's `h`. A kind 30078 from a device whose `c` is neither index is not a chunk.
8. **Treatment** (kind 30078, `c` = treatment c): the author MUST be in `devices ∪ writers`. Decrypt. `d` MUST
   equal `H(NS:tr:sid:id)`. **Writer scope:** remember the *creator* of each `id`, the key that signed the first
   version accepted (every key in `devices` counts as the wearer). A version signed by a writer is accepted only for
   a new `id` or one that writer created; if the creator is a device and the version held has `o` = `follower` (the
   wearer's copy of a writer's entry arrived first), it MAY be ignored silently, otherwise it MUST be rejected. A
   version signed by a device whose `o` isn't `follower` (the wearer's own entry) replaces a version whose creator
   is a writer whatever its `(u, o)`, and makes the wearer the creator. Otherwise merge per D§6.2.
9. **Live / alert** (21078 / 21079): the author MUST be in `roster.devices`. Decrypt, validate per D§3, D§5 and D§7.
10. Validate every payload as ogs-data.md says, and drop it if `v` is unknown.

### N§9.2 Owner

- **Inbox:** gift wraps to `I` with rumor kind 30500 and `v == 2`, deduplicated by wrap `id`.
  - **request:** apply N§7.4.
  - **rekey:** answer an approved, non-revoked `F` with the current key delivery, and a revoked one with `revoked`.
  - **history:** answer only an approved, non-revoked `F`, and only a rumor less than 1 h old, at most once per 10
    minutes overall. Republish the chunks for the hours from `since` on (at most 24 h) under the current epoch. Nothing
    is sent privately.
  - **profile:** accept only from an approved, non-revoked `F`.
- **Writer treatments:** valid signature; author in `writers` and an active follower; known `gk`; decrypts; `d`
  matches the `id`; `k` is known; and the writer MAY change or delete only entries it created. The owner keeps the
  first writer that created each `id`; an `id` already in its log with no recorded writer is the wearer's own and
  MUST NOT be changed by a writer. Accepted entries are stored with `o` = `follower` whatever the writer sent.
  (Sukrara 0.11.20 and older let a writer tombstone any entry; P4.)

## N§10 Lifecycle

- **Revoke (remove a follower):**
  1. Make `G_{n+1}`.
  2. Send key deliveries to every remaining follower and `revoked` to the removed one.
  3. Publish the roster with `epoch n+1` and switch publishing to the new key.
  4. Republish the last 24 h of chunks and 30 days of treatments.

  A revoked follower keeps what it already had. There is no forward secrecy for the past, only for the future.
- **Phone change:**
  1. Restore `I`, then fetch the owner backup (Appendix A).
  2. Make a new `D'`.
  3. Rotate to `G_{n+1}`, because the old phone knew `G_n`.
  4. Send key deliveries and a roster with `devices: [D']`.

  Followers continue without doing anything, and events from the old `D` stop being accepted.
- **Missed rotation:** a follower that sees an unknown `gk` sends a rekey request (N§9.1 step 5).
- **Missing history:** a follower whose backfill misses hours while the stream is live waits 90 s, backfills again,
  then sends a history request (at most once per 30 minutes).
- **Lost identity key:** this can't be recovered, by design. Followers must be invited again.

## N§11 Versioning and extensibility

- Payloads carry `v` (ogs-data D§10). Unknown keys are ignored, and an unknown `v` is dropped.
- Unknown message `type`s are ignored, so a new message is backwards compatible.
- New event kinds MAY be added. A follower never asks for kinds it doesn't know.
- **[P8]** Draft 2 adds `"caps":[…]` to the roster and to the access request, so each side knows which optional
  features the other implements (writers, alerts, sensor life, history requests).

## N§12 Privacy considerations

### N§12.1 No public identity on the stream (the main rule)

Anyone who reads this document can recognise a stream: kind 21078 events with a `gk` tag, a weekly 30078 by the same
author sharing that `gk`, and gift wraps to that author. **If `I` or `D` ever carries a public name, a picture or a
follow list, a real person is linked to a medical data stream.** Therefore:

- Implementations MUST NOT publish kind 0 (or kind 3, 10002, or any other public event) signed by `I`, `D`, `F`,
  `W` or a read key.
- Implementations MUST NOT use the user's main Nostr key as `I` or `F`, MUST NOT sign anything for the stream with
  it, and MUST NOT AUTH to a relay with it in the same session as the stream keys. A main key MAY be used once to
  *derive* `I` one-way (`derive_scalar(M.sec, "nostr/derived-account/v1", app + "#" + index)`). The derivation
  reveals nothing about `M`.
- Profiles exist only inside encrypted payloads ([D§8](ogs-data.md#d8-profiles)).

### N§12.2 What leaks anyway

| To whom | What |
|---|---|
| relays and the public | that a stream exists, its publishing rhythm (about one event a minute while the sensor works, which hints at a CGM), the times of rotations (`gk` changes), when followers write (author keys), the size of the history |
| relays | IP addresses, and NIP-42 AUTH keys. Use a different relay set or Tor if that matters. |
| a revoked follower | everything up to the revocation |
| anyone with an invite link before it is used | the owner's profile, and the chance to ask in place of the intended follower (the owner still decides) |
| nearby devices (Bluetooth) | see ogs-ble §8 |

### N§12.3 Data minimisation

No sensor serial on the wire. No location. No free-text fields except treatment notes (100 bytes) and names (40
characters). Relays keep live events for an hour, chunks for a week and treatments for a month. Each follower's own
database holds the long-term history.

## N§13 Security considerations

- **Authenticity** comes from signatures and the roster chain: `I` (trusted at join, through the fingerprint and the
  human's Allow) → roster → `devices` / `writers`. A follower MUST NOT accept data because of where it came from.
- **Confidentiality:** NIP-44 v2 to `G`. Anyone holding `G_n` can *read* epoch `n`. Only keys listed in the roster
  can *write*.
- **No forward secrecy** within an epoch. Rotate to cut a follower off. A key delivered over a compromised follower
  device leaks that epoch.
- **Replay:** an attacker can replay old signed events. Readings are deduplicated by `t`, treatments merge by `u`,
  and alerts older than 10 minutes are dropped. Rosters are ordered by `(epoch, created_at)`.
- **Bearer invites:** whoever holds the link can ask. The defences are the single claim, the `invite-used` notice
  to the real follower, the Allow step, and the 24 h expiry.
- **Clock trust:** `created_at` of rumors is chosen by the sender. The owner uses it only to age-limit history
  requests.
- **Denial of service:** relays can drop events. Publish to several relays. Bluetooth (ogs-ble) works without any.
- **Key storage:** `I` and `D` belong in hardware-backed storage (Android Keystore, Apple Keychain). Backing up
  `I` to a password manager is RECOMMENDED. `D` is never backed up.

## N§14 Test vectors

[`test-vectors/ogs-draft1.json`](test-vectors/ogs-draft1.json) holds keys (derived from public strings, throwaway),
derived tags, the invite, 30 signed events with their plaintexts, 3 invite checks and a **27-step acceptance
scenario**. The scenario is a sequence of events with the verdict a conforming follower must reach for each, and the
final state. NIP-44 nonces, NIP-59 timestamps and BIP-340 aux randomness are pinned, so an implementation can
reproduce the events byte for byte, not only verify them. Check with:

```sh
cd spec/reference && uv run ogs vectors verify
```

The same vectors are checked against rust-nostr 0.45.1 (the NIP-44/NIP-59 library both Sukrara apps use) by
`spec/reference/interop/sukrara_side.py oracle`.

## Appendix A — Owner backup (OPTIONAL, "portable owner")

The owner may keep its state on relays, encrypted to itself, so a new phone (or **another app**) can take over the
stream from `I` alone. Kind 30078, `d` = `H(NS:backup:I.pub)`, signed by `I`, NIP-44 from `I` to `I`, expiring after
90 days and refreshed weekly:

```json
{"v":2,"sid":"…","epochs":[{"n":1,"g":"nsec1…"}],"followers":[{"f":"<hex>","name":"Mom","avatar":{…},"since":1792000000,"revoked":false}],
 "writers":["<hex>"],"invites":[{"t":"<token>","x":1792090000}],"relays":["wss://…"],"profile":{…}}
```

Implementations MAY add private fields (the reference adds `label`, `nearby`, `sentAt`, `claimedBy` and `done` to
invites). Another app taking over MUST understand `sid`, `epochs`, `followers` and `writers`, and MUST rotate on
takeover. The backup is never sent over Bluetooth.
