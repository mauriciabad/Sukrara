# OGS3-C — Open Glucose Stream draft 3: core

`draft 3` · 2026-10-19 · [index](README.md) · data: [ogs3-data.md](ogs3-data.md) · transports:
[Nostr](ogs3-nostr.md), [Bluetooth](ogs3-ble.md) · [security](ogs3-security.md) · [rationale](rationale.md)

This document defines what a stream *is*, independent of how it travels: the participants and their keys, epochs
and the day-key tree, records and envelopes, invitations, grants, membership, the acceptance rules, and the
lifecycle (adding, removing, rotating). The record payloads are in [ogs3-data.md](ogs3-data.md); carrying
envelopes and control messages over relays or Bluetooth is in the transport documents.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY and OPTIONAL are to be
interpreted as described in RFC 2119 and RFC 8174 when, and only when, they appear in all capitals.

## C§1 Model and terms

A **stream** is one person's glucose data and everything around it (sensor state, treatments, alerts), as a set of
**records**. Each record is signed by the participant who wrote it and travels inside an **envelope** that is
encrypted with a key for one UTC **day** of one **epoch**. Access is a **grant**: a range of days in one or more
epochs, handed to a member as the minimal set of key-tree nodes that covers exactly that range.

| Term | Meaning |
|---|---|
| **wearer** | the person whose glucose it is. Usually also the administrator; for a child, a parent may be. |
| **administrator** | holds the identity key `I` of the stream. Decides who joins, issues grants and membership, rotates epochs. Exactly one device holds `I` at a time. |
| **publisher device** | a device of the wearer that writes records (reads the sensor, enters treatments, raises alerts). Key `D`. There can be several (phone, watch, second phone, desktop). Only the wearer's own devices, added by the administrator, can be publishers (C§11.6). |
| **member** | an approved participant other than the wearer's devices: a family viewer, a carer, a clinician, a watch or widget app. Key `M`. A member with no scope is a **reader**; with a scope (`write`, `ack`, `manage`) it is a **contributor**. |
| **record** | a NIP-01 event of kind 4078, signed by its author's own key, never published on its own (C§6) |
| **envelope** | a NIP-01 event of kind 31078 that carries one record, encrypted with a day key and signed by a *blinded* key (C§7) |
| **control message** | a NIP-59 gift wrap (rumor kind 4079) between the administrator and one member: invite, join, grant, ended, requests (C§9) |
| **epoch** | a key generation of the stream: a 32-byte root seed, an id, a start day. Rotating means starting a new epoch. |
| **day** | `floor(unix_seconds / 86400)`: whole UTC days since 1970-01-01 |
| **lane** | which kind of publication a blinded author key is for: `g` glucose series, `r` other records, `m` membership (C§5.3) |

A stream has exactly one identity key `I`. Two streams never share keys: an administrator who manages two children's
streams holds two identities.

## C§2 Notation

- `HMAC(k, m)` is HMAC-SHA256. `HKDF-Expand(prk, info, L)` and `HKDF(ikm, salt, info, L)` are RFC 5869 with SHA-256.
- Strings are UTF-8, and labels such as `"ogs3/tree/0"` are their ASCII bytes. `‖` is concatenation.
- `hex(x)` is lowercase hexadecimal. `x[0:n]` is the first `n` bytes.
- Keys are secp256k1. Public keys are BIP-340 x-only, 64 lowercase hex characters on the wire. Signatures are
  BIP-340 Schnorr over NIP-01 event ids.
- `derive_scalar(ikm, salt, info)` is the first `k = HKDF(ikm, salt, info ‖ [ctr], 32)` with `1 ≤ k < n` (the
  group order), for `ctr` = absent, 1, 2, … (the retry happens with probability about 2⁻¹²⁸).
- JSON is RFC 8259; producers write it compactly. NIP-44 means NIP-44 version 2.

## C§3 Constants

| Name | Value | Use |
|---|---|---|
| `KIND_ENVELOPE` | `31078` (addressable) | every stream object on relays and over Bluetooth |
| `KIND_RECORD` | `4078` | a signed record; appears only inside an envelope or a grant |
| `KIND_CONTROL` | `4079` | the rumor inside a gift wrap; appears only inside a wrap |
| gift wrap / seal | `1059` / `13` | NIP-59 |
| `DEPTH` | `10` | key-tree depth: an epoch spans `2^10 = 1024` days |
| `PAD` | `256` | record padding unit, bytes |
| `MAX_PLAINTEXT` | `65535` | largest padded record, bytes |
| invite version byte | `0x03` | C§8.1 |

All derivation labels start with `ogs3/`. A future incompatible version changes the labels and the kinds together
(C§12), so its traffic can never be mistaken for this version's.

## C§4 Epochs and the day-key tree

### C§4.1 Epoch

An epoch is `(id, start, root)`:
- `root`: 32 bytes from a cryptographically secure random generator. Only the administrator holds it.
- `id`: 8 random bytes as 16 lowercase hex characters. It names the epoch in grants and membership. It never
  appears outside encryption.
- `start`: the day number of relative day 0. The epoch covers days `start … start + 1023`. The administrator SHOULD
  choose `start = today − 30`, so recent history can be published, or republished, under a new epoch.

### C§4.2 Tree

The root is node `(0, 0)`. For level `l < DEPTH`:

```
node(l+1, 2i)   = HMAC(node(l, i), "ogs3/tree/0")
node(l+1, 2i+1) = HMAC(node(l, i), "ogs3/tree/1")
```

The **leaf** of day `d` is `node(DEPTH, d − start)`, reached from the root by the bits of `d − start`, most
significant first. Node `(l, i)` covers the relative days `i·2^(DEPTH−l) … (i+1)·2^(DEPTH−l) − 1`. A node's seed
gives every leaf below it and nothing else: HMAC can't be inverted to reach a parent or a sibling.

### C§4.3 Covers

The **cover** of relative days `a … b` (clipped to `0 … 1023`) is the minimal set of nodes whose leaves are exactly
those days: walk from the root, take a node when its range lies inside `a … b`, descend into both children when it
only overlaps, skip it otherwise. Order the result by first covered day. A cover has at most `2·DEPTH` nodes. On the
wire a node is `{"l": level, "i": index, "k": hex(seed)}`.

### C§4.4 What a grant means

A member holds, per epoch, a cover. It can compute exactly the leaves of the days its cover reaches, so it can read
and recognise exactly those days. This is how the protocol enforces:
- **the history window:** a new member's cover starts at the first day the wearer agreed to share (by default
  yesterday), not at the epoch start;
- **time-limited access:** a cover that ends on a given day gives nothing after it, with no rotation and no action
  by the administrator when it ends;
- **open-ended access:** a cover to `start + 1023`, the end of the epoch.

Shortening a grant that already reaches the future requires a rotation (C§11.2): a cover can't be taken back.

## C§5 Day material

From the leaf `L` of day `d` of epoch `e`:

| Value | Derivation | Use |
|---|---|---|
| content key | `HKDF-Expand(L, "ogs3/content", 32)` | the NIP-44 conversation key of every envelope of that day (C§7) |
| name key | `HKDF-Expand(L, "ogs3/name", 32)` | `d` tags (C§5.2) |
| blind key | `HKDF-Expand(L, "ogs3/blind", 32)` | blinded author keys (C§5.3) |
| BLE key | `HKDF-Expand(L, "ogs3/ble", 32)` | Bluetooth identifiers and proofs ([ogs3-ble.md](ogs3-ble.md)) |

### C§5.1 Content key

NIP-44 v2 takes a 32-byte conversation key. OGS uses the content key directly as that key, without ECDH, as the
open NIP-DA proposal (nostr-protocol/nips#2411) does. Encryption, padding and MAC are NIP-44's, unchanged.

### C§5.2 Names and `d` tags

Every stored object has a **name** (C§6.4). Its `d` tag is `hex(HMAC(name_key, name))[0:32 chars]`. Only holders
of the day can compute or recognise it.

### C§5.3 Blinded authors

An envelope is not signed by its author's key. It is signed by a key derived from it for one day and one lane, so
relays and onlookers see a different, unrelated public key every day, and nobody can sign as a publisher without
its secret:

```
tweak(P, lane)  = int(HMAC(blind_key, "ogs3/author/" + lane + "/" + hex(P))) mod n      (MUST be non-zero)
author(P, lane) = x( lift_x(P) + tweak·G )                                              public: any day holder
secret          = (p' + tweak) mod n,  p' = p if p·G has even y, else n − p             secret: P's owner only
```

`P` is the publisher's x-only public key (`I`, a `D` or an `M`), `lift_x` is BIP-340's (even y), `G` the generator.
Lanes:

| Lane | Publisher | Carries |
|---|---|---|
| `g` | each publisher device `D` | `cgm` records |
| `r` | each `D`, and each member with a scope | every other record type except the two below |
| `m` | the identity `I` | `membership`, `epoch_end` |

A member computes, for every day it holds, the `author(P, lane)` of every publisher the membership lists, and
subscribes to exactly those keys. An envelope by any other key is not part of the stream for it. This is the same
construction as Tor's v3 onion-service key blinding (a long-term key tweaked by a secret, time-period value) and as
BIP-341 key tweaking. BIP-340 commits the public key in every signature challenge, which is what makes signatures
under related keys safe.

Implementation notes:
- `author` is libsecp256k1's `secp256k1_xonly_pubkey_tweak_add`, and the secret is
  `secp256k1_keypair_xonly_tweak_add` (rust `XOnlyPublicKey::add_tweak` / `Keypair::add_xonly_tweak`; on platforms
  that only offer `privkey_negate` and `privkey_tweak_add`, negate first when `p·G` has odd y). Those functions refuse
  a tweak ≥ n instead of reducing it; the difference occurs with probability about 2⁻¹²⁸.
- Sign with BIP-340's default nonce derivation over the **blinded** secret (fresh auxiliary randomness RECOMMENDED).
  Never derive nonces from the unblinded key or reuse a nonce across lanes or days.

## C§6 Records

### C§6.1 Format

A record is a NIP-01 event:
- `kind` = 4078;
- `pubkey` = the author's own key (`I`, a `D` or an `M`);
- `created_at` = when it was written (seconds);
- `tags` = `[]` (MUST be empty);
- `content` = the compact JSON of the payload, an object with a string `type` ([ogs3-data.md](ogs3-data.md));
- `id` and `sig` per NIP-01 and BIP-340.

A record MUST NOT be published on its own. Implementations MUST NOT use their stream keys to sign anything else
except the NIP-59 seals of their own control messages, and NIP-42 AUTH as their inbox keys (N§5).

### C§6.2 Authorship

The signature proves who wrote a record, whoever later carries it. A record keeps its author's signature through
every copy: on relays, over Bluetooth, inside an administrator's republication, in an export. Nothing re-signs
another participant's record.

### C§6.3 The day of a record

A record is encrypted with the keys of exactly one day:

| Records | Day |
|---|---|
| `cgm` | `day(start)` (a series never crosses midnight, D§4) |
| entries: `bg`, `insulin`, `carbs`, `exercise`, `note`, and unknown types with an integer `t` | `day(t)` |
| state: `sensor`, `device`, `settings`, `alert`, `ack`, `membership`, `epoch_end`, other unknown types | `day(created_at)` |

So access to a day is access to what *happened* that day (readings, treatments) and to the state that was
*published* that day. An entry edited later is published again under its original day.

### C§6.4 Object names

| Record | Name |
|---|---|
| `cgm` | `cgm/<sess>/<start>/<len>` |
| `sensor` | `sensor/<sess>` |
| `device`, `settings`, `membership` | the type itself |
| `epoch_end` | `epoch-end` |
| `alert` | `alert/<id>` |
| `ack` | `ack/<alert>` |
| entries | `rec/<by>/<id>`, where `by` is the creator (D§9.1) |
| unknown type | `x/<type>/<id>` (`id` empty if absent) |

Envelopes are addressable per `(author, kind, d)`, and the author is per publisher, day and lane, so two publishers
never overwrite each other's objects.

## C§7 Envelopes

### C§7.1 Format

```json
{"kind":31078, "pubkey":"<author(P, lane) for the record's day>", "created_at":<publish time>,
 "tags":[["d","<dtag(name)>"],["expiration","<absolute unix seconds>"]],
 "content":"<NIP-44 v2 of pad(record JSON) with the day's content key>", "id":…, "sig":…}
```

- An envelope carries exactly one record. Its author is `author(P, lane_of(record.type))` for the record's day,
  where `P` is the record's author, or a publisher device republishing it (C§10.1 step 8).
- **Which epoch.** A record is published under the current epoch when that epoch's range contains the record's day.
  Otherwise (a day before the current epoch's start) it goes under the newest epoch whose range contains the day and
  whose last day (C§11.2) is not before it. Nothing is published under an ended epoch for a day after its last day.
- `created_at` MUST strictly increase per object (author, `d`): relays refuse an addressable replacement that isn't
  newer. A publisher that writes an object twice in one second bumps `created_at` by one.
- The tags are `d` and `expiration`, in that order, plus OPTIONAL `["-"]` (NIP-70, N§3.3). Nothing else: no `p`,
  `e`, `t` or client tag. Receivers MUST NOT depend on tag order.

### C§7.2 Padding and size

Before encryption the record JSON is padded with trailing ASCII spaces (0x20) to a multiple of 256 bytes. This is
still valid JSON, and NIP-44's own padding is applied on top. The padded plaintext MUST NOT exceed 65 535 bytes
(NIP-44's 2-byte length prefix; larger plaintexts aren't supported by every library). A `cgm` day archive (D§4.3)
fits: 1 440 rows take about 25 KB.

### C§7.3 Expiration

Every envelope carries an absolute NIP-40 `expiration`. Relays are a pipe, not an archive; each member keeps its own
history. RECOMMENDED values, which an administrator MAY change for its stream:

| Records | Expiration |
|---|---|
| `cgm` series shorter than a day | end of the record's day + 2 days |
| `cgm` day archives (`len` 86400) | end of the record's day + 30 days |
| entries | end of the record's day + 30 days |
| `alert`, `ack` | `created_at` + 2 days |
| everything else | `created_at` + 30 days |

Receivers MUST drop an envelope whose expiration has passed, whatever transport delivered it.

## C§8 Joining

### C§8.1 Invite text

```
payload = base64url_nopad( 0x03 ‖ I.pub[0:12] ‖ s )         s = 12 random bytes → 34 characters
text    = "ogs:" payload [ "@" host *( "," host ) ]          e.g.  ogs:A64LoP_BDtJ4alPhMRZP9GY54VRax1ohrw@nos.lol
web     = "https://" any-host "/" any-path "#" text          the fragment never reaches the web server
token   = base64url_nopad(s)                                 16 characters
```

- The text holds the identity's 12-byte **fingerprint**, the 12-byte invite **secret** and OPTIONAL **relay hints**
  (hostnames of relays where the invite message is, meaning `wss://host`). Nothing else: the expiry, the offer and
  the wearer's profile are inside the invite message.
- A receiver MUST accept the `ogs:` form and the `https://…#ogs:` form on any host, and MUST reject a version byte
  other than `0x03`. The host of a web form authenticates nothing; apps host their own landing pages.
- The text without hints is 38 characters: a version 3 QR code (byte mode, error correction M) holds it.

### C§8.2 Invite key

`invite_key = derive_scalar(s, "ogs3/invite", "inbox")`. Anyone holding the text can derive it; that's its purpose.

### C§8.3 Invite message

The administrator sends, as a control message (C§9) from `I` to `invite_key`, with `expiration` = the invite's
expiry (at least now + 60 s):

```json
{"type":"invite","x":<expiry>,"inbox":"<invite inbox public key>","relays":["wss://…"],
 "profile":{"name":"Alex","avatar":{…}},
 "offer":{"scopes":[],"back":1,"days":null,"auto":false,"multi":false}}
```

- `inbox`: a fresh key the administrator generated for this invite. Join messages go to it, never to `I`.
- `offer`: what the joiner will get, so the joiner sees it before asking.
  - `scopes`: C§9.3.
  - `back`: whole days of history before the day of approval (0 = from that day).
  - `days`: the access duration, or `null` for open-ended. Access ends with the UTC day `approval day + days`, so it
    always lasts at least `days` × 24 hours, wherever in the UTC day the approval falls.
  - `auto` and `multi`: C§8.7.
- The joining app accepts the message only if:
  - it opens with the invite key;
  - the rumor kind is 4079 and `type` is `invite`;
  - the seal's signer **starts with the fingerprint**;
  - `x` is in the future.

  It then shows who is inviting and what is offered. The fingerprint check stops anyone else who holds the text from
  pointing the joiner at their own identity (and feeding a carer made-up glucose): matching 12 bytes of a key costs
  about 2⁹⁶ work. 8 bytes (2⁶⁴) would not be enough here, because `I` lives as long as the stream, so the grinding is
  a one-time cost per target that every later invite, including months-long standing ones, would share.

### C§8.4 Join

The joining app generates a fresh member key `M` and a fresh inbox key, and sends to the invite's `inbox`:

```json
{"type":"join","token":"<token>","inbox":"<member inbox public key>","profile":{"name":"Mom","avatar":null},
 "caps":["cgm","alert",…]}
```

A member key MUST be used for one stream only. The inbox key is where the administrator sends this member's control
messages. It is a different key from `M`, so `M` never appears outside encryption.

### C§8.5 Match code

```
match_code(M, token) = int(sha256(hex(M.pub) + ":" + token), big-endian) mod 1 000 000, as 6 digits
```

The joining app shows it. The administrator's app shows the code of the join it received.
- **QR or link invites:** the wearer SHOULD compare the two codes.
- **Nearby (Bluetooth) invites:** the wearer MUST **type** the joiner's code into the administrator's app, and the
  administrator MUST refuse the join if it differs, because every device in range sees the invite (B§7).

### C§8.6 Decision and single claim

- The administrator accepts a join only with a known, unexpired token, on that invite's inbox.
- The **first** valid join it *receives* claims the invite. A later join on the same token from another key is
  answered with `ended` (`reason: "claimed"`), and the wearer is warned.
- A join from a key that is already a member never changes anything: it is ignored if the member is active, and
  answered with `ended` if the member was removed. A replayed join MUST NOT re-admit anyone. (The live run in the
  reference found exactly this bug in the first version of the reference administrator.)
- Unless the invite says `auto`, the administrator MUST get an explicit decision from a human (Allow or Decline).
  Names and pictures are self-asserted, and approving shares history at once, which can't be taken back.
- **Allow** → C§11.1. **Decline** → `ended` with `reason: "declined"`. Either way, a single-use invite is used up.

### C§8.7 Standing invites and emergency access (OPTIONAL)

An invite with `"multi": true` can be claimed by several joiners. With `"auto": true` it is approved without a
human. Together they make an **emergency invite**: printed on a medical card or shown on a lock screen, so a
paramedic or a school can see the glucose when the wearer can't approve anything. Rules:
- An invite with `auto` MUST have no scopes: an anonymous holder of the card must not be able to write entries or
  acknowledge alerts (an `ack` can make publishers stop repeating an alert that carers rely on). Administrators MUST
  refuse to create such an invite. It SHOULD have `back` ≤ 1 and `days` ≤ 1.
- Each automatic approval MUST notify the wearer, and SHOULD notify members with the `manage` scope.
- The administrator SHOULD rate-limit approvals per standing invite (RECOMMENDED: 3 a day) and MUST let the wearer
  revoke the invite (forget the token) at any time. The limit is a trade-off: whoever holds the card can also use it
  up for the day.
- The invite message's `expiration` can be long (months). The administrator republishes it before it expires.
- It works only while the administrator's device is online, because that device approves the join.

Whoever photographs the card can read the stream for a day or two. That is the trade-off the wearer accepts by
enabling it.

## C§9 Control messages

### C§9.1 Format

Every control message is a NIP-59 gift wrap:
- the **rumor** is an unsigned event of kind 4079, with `tags` `[]`, `created_at` = now, and `content` = the
  compact JSON of the message, an object with a string `type`;
- the **seal** (kind 13) is signed by the sender's own key (`I` or `M`);
- the **wrap** (kind 1059) is signed by a one-time key, tagged `["p", <recipient inbox>]` and
  `["expiration", <absolute>]`.

The seal's signer is the authenticated sender. Receivers MUST check that the rumor's `pubkey` equals it. Receivers
MUST deduplicate by wrap `id`, and MUST ignore a `type` they don't know.

| type | from → to | TTL | body |
|---|---|---|---|
| `invite` | `I` → invite key | until `x` | C§8.3 |
| `join` | `M` → invite inbox | 7 d | C§8.4 |
| `grant` | `I` → member inbox | 30 d | C§9.2 |
| `ended` | `I` → member inbox | 30 d | `{"reason":"removed"\|"expired"\|"declined"\|"claimed"\|"left"}` |
| `invite-link` | `I` → member inbox | 7 d | `{"text":"ogs:…"}` (answer to `manage` op `invite`) |
| `rekey` | `M` → administrator inbox | 7 d | `{"have":["<epoch id>",…]}` |
| `history` | `M` → administrator inbox | 1 d | `{"from":<day>,"to":<day>}` |
| `profile` | `M` → administrator inbox | 7 d | `{"profile":{…}}` |
| `inbox` | `M` → administrator inbox | 7 d | `{"inbox":"<new member inbox>"}` |
| `leave` | `M` → administrator inbox | 30 d | `{}` |
| `link` | `M` → administrator inbox | 7 d | `{"k":"<new member key>","inbox":"<its inbox>","label":"Watch"}` |
| `manage` | `M` → administrator inbox | 7 d | `{"op":"remove","k":…}` or `{"op":"invite","offer":{…}}` |

The **administrator inbox** is a key per epoch, generated with the epoch and announced in the membership (C§9.3).
The administrator listens on the inboxes of the current and recent epochs and on every open invite's inbox. Inbox
keys are the only stream keys that appear on relays (as `p` tags), and each lasts one epoch or one invite.

### C§9.2 Grant

```json
{"type":"grant","current":"<epoch id>",
 "epochs":[{"id":"<epoch id>","start":<day>,"depth":10,"cover":[{"l":9,"i":1,"k":"<hex>"},…],
            "membership":<that epoch's newest membership record (C§6.1), signed by I>},
           {"id":"<older epoch id>","start":<day>,"depth":10,"cover":[…],"end":<its last day>,
            "membership":<its last membership record>},…],
 "grant":{"scopes":["write"],"from":<day>,"to":<day>|null}}
```

- `epochs` lists every epoch whose days overlap the grant window and that holds data the member may read: the current
  one, covering `from … to`, or to its end when `to` is `null`; and older ones, covering the overlap up to their
  last day (C§11.2), which they carry as `end`. Only older epochs have `end`.
- Each epoch carries its newest `membership`, so the member can authenticate that epoch's publishers at once, before
  it fetches anything, and even after the membership envelopes have expired from relays (a clinician's 90-day
  history).
- A member MUST accept a grant only from `I`, and only if every listed epoch has a valid membership record signed
  by `I` for that epoch. Grants add: a member keeps every node it has received, except that it never keeps seeds of
  an ended epoch beyond its `end` (C§11.2). It keeps the highest `seq` per epoch.
- `grant` and `ended` are ordered by their rumor `created_at` (C§10.2).
- A grant must fit NIP-44's 65 535-byte plaintext inside the seal and again inside the wrap (about 45 KB of JSON).
  An administrator that needs more (many epochs) sends several grants: they add.

### C§9.3 Membership

The administrator publishes, as a record of type `membership` signed by `I` in lane `m`, the authority for the
epoch:

```json
{"type":"membership","epoch":"<epoch id>","seq":3,
 "devices":[{"k":"<D>","name":"Phone"}],
 "members":[{"k":"<M>","scopes":["write","ack"],"from":<day>,"to":<day>|null}],
 "inbox":"<administrator inbox for this epoch>","relays":["wss://…"],
 "profile":{"name":"Alex","avatar":{…}},"caps":["cgm","sensor","alert",…]}
```

- `seq` starts at 1 per epoch and increases with every change. Members keep the highest `seq` per epoch.
- `devices`: the publisher devices. They may publish every record type in lanes `g` and `r`.
- `members`: **only members with at least one scope**, and their window. Readers aren't listed: they write
  nothing, so nobody needs to verify them, and members don't learn about each other.
- Scopes: `write` (entries, D§9), `ack` (acknowledge alerts, D§8.3), `manage` (ask the administrator to invite or
  remove; it executes them, C§11.9).
- `relays`: where the stream is published. `profile`: the wearer's profile (D§10). `caps`: C§12.
- The administrator MUST publish the membership on every change and at least once on every day the stream publishes
  anything, so a member holding only that day can still authenticate it.

### C§9.4 Administrator processing

For each wrap on one of its inboxes, deduplicated by wrap `id`:
- **join:** C§8.6.
- **rekey:** an active member gets a fresh grant; a removed one gets `ended`.
- **history:** only from an active member, only for days inside its window and not after today, and at most 31
  days per request. The administrator republishes those days as `cgm` day archives (D§4.3), plus the entries of
  those days, under an epoch the member holds (C§7.1). Nothing is sent privately. Rate limit: RECOMMENDED one request
  per member per 10 minutes. A member can't read a day it has punctured (C§11.4): it requests history before it
  punctures.
- **profile, inbox:** update the member's record.
- **leave:** remove the member (C§11.2) without an `ended` notice.
- **link:** C§11.8. **manage:** C§11.9.
- Anything from a key that isn't a member is ignored.

## C§10 Acceptance rules

These rules are the protocol's security. Every receiver applies them to every envelope and control message from any
relay, over Bluetooth, or from a file. Where an event came from earns it no trust.

### C§10.1 Envelopes

In this order (the reference returns the reason shown in brackets, and the test-vector scenario expects them):
1. Only kind 31078 is an envelope (other kinds: *ignore* [`kind`]). The NIP-01 `id` and the BIP-340 signature
   verify. Otherwise *reject* [`bad-signature`].
2. Already seen `id` → *ignore* [`duplicate`].
3. NIP-40 expiration has passed → *ignore* [`expired`].
4. The member has been ended (C§10.2) → *ignore* [`ended`].
5. Look the `pubkey` up among the blinded authors the member computed for the days it holds (C§5.3). Not found →
   *ignore* [`unknown-author`], without remembering the `id` (a later grant or membership may make it readable).
   Found → this gives the epoch, the day, the publisher `P` and the lane. Remember the `id`.
6. Decrypt with the day's content key. Failure → *reject* [`decrypt`].
7. Parse the record. Check kind 4078, empty tags, `id` and signature, a `type`, and the payload rules of
   [ogs3-data.md](ogs3-data.md). Failure → *reject* [`bad-record`].
8. The record's author must be `P`, unless `P` is a publisher device (devices may republish any record they
   accepted). Otherwise *reject* [`rewrapped`].
9. The record type's lane (C§5.3) must be the envelope's lane. Otherwise *reject* [`wrong-lane`].
10. The record's day (C§6.3) must be the envelope's day. Otherwise *reject* [`wrong-day`].
11. The `d` tag must equal `dtag(name)` (C§6.4). Otherwise *reject* [`d-mismatch`].
12. The author must be allowed to write that type (C§10.3). Otherwise *reject* [`not-identity`, `not-device`,
    `scope`].
13. Merge (C§10.5).

### C§10.2 Control messages (member side)

1. Deduplicate by wrap `id`. Drop expired wraps. Only wraps whose `p` is the member's inbox.
2. Unwrap per NIP-59. The rumor kind is 4079. The seal's signer must be `I`, as learnt from the invite →
   otherwise *reject* [`not-from-identity`].
3. **grant:** C§9.2. The member becomes active. **ended:** the member stops accepting envelopes and keeps what it has.
   The two are ordered by rumor `created_at`, never by arrival, because relays can deliver them in any order and
   again after a restart: a grant not later than the last `ended` is ignored [`grant-before-ended`], and an `ended`
   earlier than the last accepted grant is ignored [`ended-before-grant`]. On equal times `ended` wins.
4. **An `epoch_end`** record, or a grant giving an epoch an `end`, ends that epoch for the member: it cuts its cover
   at the last day (`day(at)`, or `end`; the earlier if both), and stops computing authors beyond it (C§11.2).
5. **After an `epoch_end`** of its current epoch, a member waits up to 2 minutes for a grant of a newer epoch. If none
   arrives, it sends `rekey`. At most one `rekey` per 5 minutes.

### C§10.3 Who may write what

| Record type | Publisher device | Member with `write` | Member with `ack` | `I` |
|---|---|---|---|---|
| `cgm`, `sensor`, `device`, `settings`, `alert` | ✓ | | | |
| `bg`, `insulin`, `carbs`, `exercise`, `note` | ✓ | ✓ (own entries; C§10.5) | | |
| `ack` | ✓ | | ✓ | |
| `membership`, `epoch_end` | | | | ✓ |
| unknown types | ✓ | ✓ | | |

A member's rights apply only to records whose day is inside its `from … to` in the membership. Rights come from
the newest membership of the envelope's epoch (C§10.1 step 12). A key removed from it loses them for every record in
that epoch: once a member holds the epoch's last membership (C§11.2), from the relays or from its grant, it MUST
reject every record of that epoch signed by a removed key, whenever the record was signed. A member that was offline
therefore loses the removed participant's last records that it hadn't received yet. That is accepted on purpose
(rationale D15).

### C§10.4 Contributors

A member with a scope publishes its records itself: signed by `M`, in an envelope signed by `author(M, "r")` for the
record's day. There is no echo and no re-signing. The administrator and every other member apply C§10.1 to them like
any other envelope. A publisher device MAY republish a member's record, unchanged, to keep it available.

### C§10.5 Merge

- **`cgm`:** per object (author, name), the newest `created_at` wins. Readings are keyed by `(sess, t)`. A reading
  from a newer object replaces the one held, so a later version can **correct** a value or add a missing rate. A
  correction can't delete a reading; a producer marks it `unreliable` instead (D§4.2).
- **Entries:** keyed by `(by, id)`, where `by` is the creator (D§9.1). A version from an author other than `by` is
  accepted only from a publisher device [`not-creator` otherwise]. Of two versions, the one with the greater
  `(rev, author)` wins, compared as `(integer, lowercase hex)`. The `type` of an entry never changes
  [`type-changed`]. Deletions are tombstones (`"del": true` with a higher `rev`).
- **`sensor`** per `sess`, **`device`** per author, **`settings`** per stream and **`alert`** per `id`: the greater
  `rev` wins.
- **`ack`** per (alert, author): the newest `created_at` wins.
- **`membership`** per epoch: the greater `seq` wins. The administrator never publishes two different memberships
  with the same `seq`.
- **Ties.** When two versions compare equal on the rules above (two devices publishing `settings` with the same
  `rev`, two series with the same `created_at`), the record with the greater `id` (lowercase hex) wins.

All replicas converge whatever order envelopes arrive in. (One exception, by design: a member that receives a
removed key's records only after the removal rejects them, while one that got them earlier keeps them. C§10.3.)

## C§11 Lifecycle

### C§11.1 Adding a member

On Allow, the administrator:
1. records the member: key, inbox, scopes, `from` = approval day − `back`, `to` = approval day + `days` or none;
2. if the member has a scope, increments `seq` and publishes the membership;
3. sends a grant (C§9.2).

No rotation is needed: the new member only gets nodes for its window.

### C§11.2 Removing a member (rotation)

1. Mark the member removed.
2. If the removed key is listed in the old epoch's membership (a device or a contributor), publish in the **old**
   epoch a last membership (`seq` + 1) without it.
3. Publish, in the old epoch, an `epoch_end` record (`{"type":"epoch_end","at":<now>}`) signed by `I`. The epoch's
   **last day** is `day(at)`.
4. Start a new epoch (C§4.1) with a new administrator inbox.
5. Publish its membership (`seq` 1), without the removed member.
6. Send a grant (C§9.2) to every remaining member: the new epoch, and the old one with `end` and its last membership.
   SHOULD send `ended` (`reason: "removed"`) to the removed one.
7. Publish from now on under the new epoch, and never again under the old one for a day after its last day.

A member that learns that an epoch ended, from `epoch_end` or from a grant, cuts that epoch's cover at the last day
(C§10.2). This matters because covers handed out before the rotation reach the epoch's end, up to three years ahead,
and the removed member, or a lost phone, still holds those seeds and its own signing key. Without the cut and the
last membership, it could keep publishing entries, or glucose, into the old epoch, and the remaining members would
accept them. With both, nothing it signs in the old epoch is recognised: its blinded authors are no longer computed.

The removed member keeps what it could already read: the old epoch up to its cover. Removal is about the future. It
can't recognise anything published under the new epoch: new blinded authors, new `d` tags, new BLE identifiers.

The administrator MUST rotate:
- when it removes a member or a publisher device;
- when it shortens a grant that reaches the future;
- before the epoch's last day.

It SHOULD rotate at least every 30 days (C§11.5).

### C§11.3 Time-limited grants

A grant with `to` ends by itself: after that day the member's cover yields no keys. The administrator SHOULD send
`ended` (`reason: "expired"`) once it has passed, and removes the member from the membership at the next change. No
rotation is needed.

### C§11.4 Forward secrecy for members (puncturing)

A member SHOULD delete key material for days it no longer needs: replace each cover node by the sub-nodes that cover
only days ≥ the first day it keeps, and erase the rest. After that, a compromise of the member's device exposes no
older envelope still stored on relays. Whether to keep the decrypted history is the app's own business (S§5).

The first day it keeps is the start of its **edit window** (RECOMMENDED: today − 7): the days whose entries can still
be edited and that it watches on relays (N§4.1). Puncturing earlier would make edits and history republications of
those days unreadable. A member that wants older history (a clinician) backfills or requests it (C§9.4) first, and
punctures after.

### C§11.5 Scheduled rotation (post-compromise security)

If an epoch's root or a member's nodes leak without the device being compromised for good, the next rotation shuts
the attacker out. Rotating every 30 days bounds that window. The cost is one grant per member.

### C§11.6 Publisher devices and phone change

- **Who can be a publisher.** Publisher devices are the wearer's own devices running the administrator's app (or
  an app the wearer installs as part of it, on a device the wearer controls). Only they hold epoch roots, appear in
  `devices`, and publish device records (`cgm`, `sensor`, `device`, `settings`, `alert`). An administrator MUST NOT
  add to `devices`, or send epoch roots to, a key it learnt through an invite, a `join`, a `link` or a `manage`
  request, or any other key of a third-party app or device. Third-party apps and devices (another vendor's watch,
  a follower app, a home-automation bridge) take part only as **members**: readers, or contributors with `write` or
  `ack` that sign with their own key in lane `r` (C§10.4). `link` (C§11.8) adds a member's devices as members, never
  as publishers. Draft 3 defines no protocol for handing epoch roots to another app (rationale D16).
- **Adding a device:** the administrator generates or receives its key `D` and adds it to `devices`. The device
  needs the full tree (the root): the administrator sends it, over an authenticated channel of its own choosing, the
  epoch roots. This document doesn't define that channel: it is internal to the administrator's app and the
  wearer's own devices. It increments `seq` and publishes the membership.
- **Removing or replacing a device** (the phone was lost) is a removal: rotate (C§11.2).
- **Moving the administrator** to a new phone: restore `I` (and, if used, the backup, C§13), create a new `D`,
  rotate. Members continue without doing anything.

### C§11.7 Pause

To pause a member, remove it (C§11.2) without an `ended` notice and add it again later with a fresh grant whose
`from` is the resume day. It can't read the gap.

### C§11.8 A member's other devices

A member's second device (a watch, a tablet) MAY join with its own invite. An active member MAY instead send `link`
with the new device's key and inbox. The administrator MAY approve it without a human, per the wearer's settings,
giving the same scopes and window. Each device stays a separate member key, so it can be removed alone. A linked
device is a member, never a publisher device (C§11.6).

### C§11.9 Delegated management (OPTIONAL)

A member with `manage` MAY send:
- `manage` `{"op":"remove","k":…}`: the administrator removes that member (only a member: never a publisher device
  or `I`);
- `manage` `{"op":"invite","offer":{…}}`: the administrator creates an invite and answers with `invite-link`.

The administrator MAY ask its own human first. It MUST NOT grant `manage` through such a request. This is how two
parents share the management of a child's stream while one device holds `I`.

### C§11.10 Lost identity key

A lost `I` can't be recovered. Members must be invited again under a new identity. Backing up `I` (to a password
manager, or with C§13) is RECOMMENDED.

## C§12 Versioning and extensibility

- **Capabilities.** The membership's and the join's `caps` list what each side implements. Values defined here:
  - every record type name;
  - `history`: the administrator answers history requests;
  - `ble`: nearby transport;
  - `link`, `manage`: C§11.8, C§11.9;
  - `standing`: C§8.7.

  Receivers ignore unknown caps.
- **Unknown record types** are kept and passed on unchanged (they may be republished), and are never interpreted
  (D§12). **Unknown fields** are ignored. **Unknown values of open enumerations** (D§1) are kept and shown
  generically.
- **Unknown control message types** are ignored.
- **A breaking change** to a record's meaning gets a new type name. A breaking change to keys, derivations or
  envelopes is a new major version: new kinds and a new label prefix (`ogs4/`). The two can run side by side
  without confusion.

## C§13 Owner backup (OPTIONAL)

An administrator MAY keep its state on relays so a new phone, or another app, can take the stream over from `I`
alone:
- period: `q = floor(day / 30)`;
- author: `derive_scalar(I.sec, "ogs3/backup", "author/" + q)` (decimal `q`), so the backup's key changes every 30
  days instead of being one key that publishes for years;
- kind 31078, `d` = `hex(HMAC(HKDF(I.sec, "ogs3/backup", "d", 32), "ogs3/backup/" + q))[0:32 chars]`, opaque like
  every other `d` tag (a literal `"backup"` would let anyone list every OGS administrator);
- a new phone looks for the current and the previous three periods;
- content: NIP-44 with the conversation key `HKDF(I.sec, "ogs3/backup", "key", 32)`, holding
  `{"type":"backup","epochs":[{"id","start","root"}],"inboxes":{"<epoch id>":"<hex secret>"},"members":[…],
  "invites":[…],"devices":["<D>"],"profile":{…},"relays":[…]}`;
- refreshed at least weekly, expiring after 90 days.

Another app taking over MUST rotate. Draft 3 has no test vector for the backup yet.

## C§14 Test vectors

[`test-vectors/ogs-draft3.json`](test-vectors/ogs-draft3.json):
- the tree: two epochs, 5 covers, the material of two days, blinded authors and a blinded secret, `d` tags, a
  punctured cover;
- the invite (three text forms) and 3 invite checks;
- 44 events with every wrap's plaintext;
- 3 administrator checks (join, claimed, unknown token);
- a **42-step acceptance scenario** with the expected verdict per step and the final state, including a removed
  writer and a stale publisher signing in an ended epoch, and an `ended` older than the newest grant;
- Bluetooth identifiers, `HELLO`/`INFO`/`AUTH` and frames at 3 MTUs;
- the informative bridge mappings.

Every key, nonce and timestamp is pinned, so an implementation can reproduce the events byte for byte, not only
verify them. `uv run ogs d3 vectors verify` in `spec/reference` checks a file.
