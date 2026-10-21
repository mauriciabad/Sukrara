# CGM-over-Nostr protocol — draft v2 (investigation 06)

Changes vs. [v1](PROTOCOL.md): an **identity key** separate from the publishing **device key**, a **stream id** for
tags, **epoch-versioned read keys** delivered privately per follower (no secret ever in a QR/link), **invite →
request → approve** sharing, **revocation by rotation**, and an **owner backup** so a new phone restores everything
from one password-manager entry. Design notes and sources: [docs/06](../../docs/06-sharing-and-keys.md).
Modelled on the open NIP-DA proposal (nostr-protocol/nips#2411, scoped data grants).

## Keys

| Key | Held by | Purpose |
|---|---|---|
| **Identity `I`** | owner app (Keystore-encrypted) + **password manager** backup | the stream's identity followers trust; signs the roster, key deliveries, owner backup. Rarely used. |
| **Device `D`** | one phone (Keystore-encrypted), never backed up | signs live events + chunks. Replaced on phone change. |
| **Read key `G_n`** (epoch `n` ≥ 1) | owner + approved followers | NIP-44 v2 target of all data: `nip44(D.sec → G_n.pub)`. New epoch on every revocation / phone change. |
| **Stream id `sid`** | owner + approved followers | 32 random bytes, never rotates; only used to derive opaque tags |
| **Follower `F`** | each follower app | identifies a follower; receives key deliveries |

`gk_n = hex(sha256(G_n.pub))[:8]` names an epoch in tags (reveals *that* a rotation happened, nothing else).
All tags/ids below: `H(x) = hex(sha256(x))[:32]`.

## Events

All content is JSON, NIP-44 v2 encrypted. Every event has a NIP-40 `expiration`.

| Event | kind | signed by | tags | content (decrypted) |
|---|---|---|---|---|
| **live** | 21078 | `D` | `["gk", gk_n]`, exp now+1 h | `{"v":2,"t":unix_s,"sgv":mgdl,"r":rate|null,"dev":src,"ss"?,"se"?,"sw"?}` → `G_n`; or reading-less `{"v":2,"ss","se","sw"}` ([sensor life](#sensor-life)) |
| **hour chunk** | 30078 | `D` | `["d", H("cgm2:h:"+sid+":"+hourStart)]`, `["c", H("cgm2:glucose:"+sid)]`, `["gk", gk_n]`, exp hour+7 d | `{"v":2,"h":hourStart,"dev":src,"r":[[dt,sgv,rate100],…]}` → `G_n` |
| **roster** | 30078 | `I` | `["d", H("cgm2:roster:"+sid)]`, `["gk", gk_n]`, exp now+30 d (refreshed weekly) | `{"v":2,"epoch":n,"devices":[hex D…],"writers":[hex W…],"relays":[…],"profile":{…}}` → `G_n` (`profile`: the wearer's, see [Profiles](#profiles)) |
| **owner backup** | 30078 | `I` | `["d", H("cgm2:backup:"+I.pub_hex)]`, exp now+90 d (refreshed weekly) | `{"v":2,"sid","epochs":[{"n","g":nsec}],"followers":[{"f":hex,"name","avatar","since","revoked"?}],"invites":[{"t","x"}],"relays":[…],"profile":{…}}` → **`I` itself** |
| **invite** | rumor kind 30502 in gift wrap to `invite_key(secret)` | seal by `I` | wrap exp = invite expiry | `{"v":3,"type":"invite","x":expiry,"r":[relays],"profile":{…}}` ([PROTOCOL-identity](PROTOCOL-identity.md#short-invites-v3)) |
| **access request** | rumor kind 30500 in NIP-59 gift wrap (1059) to `I` | seal by `F` | — | `{"v":2,"type":"request","token":t,"profile":{"name":"Mom","avatar":{…}}}` |
| **profile update** | rumor kind 30500 in gift wrap to `I` | seal by `F` | — | `{"v":2,"type":"profile","profile":{…}}` (applied only for an approved, non-revoked `F`) |
| **key delivery** | rumor kind 30501 in gift wrap to `F` | seal by `I` | — | `{"v":2,"type":"key","sid","epoch":n,"g":nsec(G_n),"devices":[…],"relays":[…]}` |
| **rekey request** | rumor kind 30500 in gift wrap to `I` | seal by `F` | — | `{"v":2,"type":"rekey","have":gk}` (follower saw an unknown `gk`) |
| **history request** | rumor kind 30500 in gift wrap to `I` | seal by `F` | — | `{"v":2,"type":"history","since":hourStart}` (follower's backfill found hours missing) |
| **revoked notice** | rumor kind 30501 in gift wrap to `F` | seal by `I` | — | `{"v":2,"type":"revoked"}` (courtesy; security comes from rotation) |
| **invite used** | rumor kind 30501 in gift wrap to `F` | seal by `I` | exp +7 d | `{"v":2,"type":"invite-used"}`: another follower's request had already claimed this invite |
| **declined** | rumor kind 30501 in gift wrap to `F` | seal by `I` | exp +7 d | `{"v":2,"type":"declined"}`: the owner denied the request (no key follows) |

Gift wraps (kind 1059) are signed by a random one-time key and `p`-tag the recipient; the **seal's signer** is the
authenticated sender. Their NIP-40 `expiration` is **absolute** (now + TTL): NIP-59 backdates the wrap's `created_at`
by up to 2 days, so an expiration computed from it makes anything shorter than 2 days (a 24 h invite, a 1 d history
request) arrive already expired ("invalid: event expired", found 2026-10-17). Followers accept a key delivery **only if the seal is signed by `I`**; the owner accepts a
request only with a valid, unexpired invite token (and the owner still confirms it in the UI).

TTLs (Android and Mac, both from now): access request, profile update, rekey request, invite used and declined **7 d**;
history request **1 d**; key delivery and revoked **30 d**; the invite wrap until the invite expires. A key delivery
whose `sid` differs from the one the follower already has is rejected: a stream's `sid` never changes.

### Relay rules (learned while testing, 2026-10-16)

- **NIP-42 AUTH**: some relays only serve kind 1059 `#p` subscriptions to the recipient. Answer
  `["AUTH", challenge]` with a kind 22242 event signed by the key the wraps are addressed to (owner → `I`,
  follower → `F`, invite lookup → the invite key) and re-issue the REQs.
- **Default relays** (Sukrara 0.11): nos.lol, relay.primal.net, nostr.mom, relay.snort.social. Each must take events
  from brand-new keys (every gift wrap is signed by one) and serve wraps back to their recipient
  ([relay_probe.py](identity/relay_probe.py), [relay_burst.py](identity/relay_burst.py),
  [results](results/relay-probe-2026-10-17.txt)). Not usable: relay.damus.io and relay.nostr.net (accept wraps,
  serve them to nobody: AUTH "needs serviceUrl"); offchain.pub and nostr.bitcoiner.social (refuse a new key after
  8–10 events, "not in our web of trust"); nostr.oxtr.dev (rate-limits a 25-chunk start); nostr-pub.wellorder.net
  ("spam" after ~47 events).
- **Addressable events need strictly increasing `created_at`** per (author, kind, `d`) — two versions in the same
  second are rejected ("replaced: have newer event"), which can leave a stale owner backup.
- Gift wraps' outer `created_at` is randomised up to 2 days into the past (NIP-59): subscribe with `since` ≥ 3 days.

## Treatments (insulin / carbs / notes) — v2 addition, 2026-10-16

Event format = [libreview SPEC §5](../libreview/SPEC.md); this section fixes who publishes what and how followers
and writers use it. Same bytes over relays and over BLE (`["EVENT", ev]`, [docs/07](../../docs/07-bluetooth-followers.md)).

| Event | kind | signed by | tags | content (decrypted) |
|---|---|---|---|---|
| **treatment** | 30078 | `D` (owner's merged log) or a writer `W` (its own entries) | `["d", H("cgm2:tr:"+sid+":"+id)]`, `["c", H("cgm2:treatments:"+sid)]`, `["gk", gk_n]`, exp `created_at`+30 d | `{"v":1,"id","k","a","t","n","u","del","o"}` (SPEC §1 without `lv`) → `G_n`, NIP-44 from the signer |

- `created_at` = publish time, **strictly increasing per (author, `d`)** (relays drop a same-second replacement).
  `u` (ms, inside the content) is the merge version; `created_at` is not.
- `o` is kept as created: `phone`, `mac` (legacy-link entries) or `follower` (writer entries; **every writer sends
  `follower`**, the Mac included). Deletes are the same event with `"del":true` (tombstone, never dropped from the log).
- **Writer scope** (investigation 24, P4): a writer may create entries and change or delete **only the entries it
  created**; the wearer may change any entry. Every receiver remembers the *creator* of each `id`: the key that signed
  the first version it accepted (any roster device counts as the wearer).

**Owner** (the wearer's phone) publishes its **whole merged log** signed by its device key `D` (the key in
`roster.devices`, the one that signs live/chunks): its own entries, the Mac's (legacy link), accepted writer
entries, deletes.
- On every change of the log: that entry, at once (CONTROL lane, paced ≥ 2 s per relay, replaced by `d` in the queue).
- **Republish** every entry with `t` ≥ now − 30 d (tombstones too), paced: on an **epoch rotation** (encrypted to the
  new `G`), when a **new follower is approved**, and at least **weekly** (keeps the 30-day expiry rolling).
- BLE: every treatment event is pushed to subscribed centrals like any publish; the `HELLO` backlog also carries the
  treatment events of the last 30 days (newest 200), after the chunks and before live.
- Sukrara for Mac follows over v2 like any follower; while it is a writer, its entries go over v2 only (not the
  legacy Mac link), so "Can add entries" governs the Mac too. The legacy link (SPEC §2) refuses entries unless the
  wearer explicitly allows that unauthenticated path on the phone.
- The owner accepts a writer event only if the author ∈ roster `writers` ∧ active follower (docs/07) **and** the `id`
  is new or that writer created it (the owner keeps the first writer per `id`; an `id` in its log with no writer is the
  wearer's own), merges it (§1) into the log, and so re-publishes it signed by `D` (**the echo**; a BLE writer event is
  also forwarded as-is to the other BLE centrals).

**Followers** (Android + Mac) receive treatments:
- `REQ {"kinds":[30078], "#c":[H("cgm2:treatments:"+sid)], "authors": roster.devices ∪ roster.writers,
  "since": now − 30 d}`; re-issued when the roster's devices or writers change. Over BLE they arrive unasked.
- Accept iff: valid signature; author ∈ current `roster.devices ∪ roster.writers`; `gk` names a known epoch
  (unknown `gk` from a device ⇒ rekey request, as for chunks); NIP-44 decrypt with `(G_n.sec, author.pub)`;
  `d` == `H("cgm2:tr:"+sid+":"+id)`; `k` ∈ `rapid|long|carbs|note`; **writer scope**: a writer's version only for a
  new `id` or one that writer created. When the wearer's copy of a writer's entry (`o` = `follower`) came first, the
  writer's own copy is ignored (the echo already holds it), not counted as a rejection. A wearer's own entry (`o` ≠
  `follower`) signed by a device replaces a version a writer created first for that `id`, whatever its `u` (a writer
  that forged the id before the real entry reached this follower).
- Merge with SPEC §1 (newest `(u, o)` wins), persist in the follower's own DB, draw them, serve them from the
  follower's Nightscout API (`/api/v1/treatments`, SPEC §5 mapping).

**Writers** (followers in roster `writers`):
- Show *Add entry* only while the follower's own key `F` is in the newest roster's `writers` (live: a roster without
  it hides the form at once; otherwise "*<wearer> hasn't allowed you to add entries*").
- Per entry the writer sent: *sending* → *sent* (a relay `OK true` or the BLE write response) → **accepted** when an
  event for the same `id` signed by a **roster device key** arrives with `u ≥` the sent `u` (the echo). No echo after
  ~2 min ⇒ shown as *not accepted yet* (keeps listening; the owner may be offline). There is no explicit NACK:
  a rejected entry simply never echoes.

Verified on Android (two emulators, 2026-10-16, `spikes/showcase-android/evidence/n1-*`): wearer entries and deletes
reach the follower over relays and over BLE with the follower offline; writer entry accepted (echo) in < 5 s over
relays, ≈ 0.1 s over BLE, and after 3 min when the wearer's relays were off (*not accepted yet* → *accepted*).

## Alerts — v2 addition, 2026-10-17 (investigation 15)

The wearer's app sends every glucose alert it fires or clears, so followers can alert at the same moment. Rules and
settings: [`spikes/alerts/SPEC.md`](../alerts/SPEC.md); report: [docs/15](../../docs/15-alerts.md).

| Event | kind | signed by | tags | content (decrypted) |
|---|---|---|---|---|
| **alert** | 21079 (ephemeral) | `D` | `["gk", gk_n]`, exp now+1 h | `{"v":2,"type":"alert","id","k","state":"fire"\|"clear","at","t","sgv","r","th","n","tr"}` → `G_n` |

- `k` ∈ `very_low|low|high|very_high|extremely_high|falling_fast|rising_fast|no_data`; `id` is random per firing and a `clear`
  repeats it; `n` counts the episode's alerts; `tr` = treatments the headline acknowledges (0, 1, 2 = two or more:
  "Low glucose · eat more again"). A treatment-muted alert (after rapid insulin or carbs, falling back into range) is
  never sent. The wearer's alert notification actions (Mute / Slow down) are per device and never sent: the events are
  what the rules decide without them, so followers keep alerting while the wearer has muted its phone (SPEC §2,
  2026-10-21).
- Sent at once on relays (LIVE lane, also when the upload interval batches readings) and over BLE (backlog key
  `alert`, next to `live`).
- Followers subscribe with `kinds: [21078, 21079]` in the live REQ, apply the live acceptance rules, dedupe by event
  id, drop `type ≠ "alert"` and anything with `at` older than 10 min.

## Sensor life — v2 addition, 2026-10-18 (round sensor-life)

The live event carries the owner's current sensor (unix s): `ss` = its start, `se` = its end (start + wear length;
the Libre 3 Plus says 15 days in its patch info; earlier when the sensor reported that it ended), `sw` = its warm-up's
end (start + the patch info's warm-up, 60 min). All three are left out when the owner knows no sensor. Juggluco as the
owner's source gives the start only (`sgv.json?sensor=Y`): end = start + 15 days, warm-up 60 min.

- Every reading's live event carries them. A new sensor has no readings for its warm-up, so the owner also publishes
  a **reading-less live event** `{"v":2,"ss","se","sw"}` (same kind, envelope and expiry) whenever its sensor info
  changes (new sensor, ended early) and every chunk tick (5 min) while the sensor warms up.
- Followers and the Mac take the sensor from the live event with the newest `created_at` (an older one from a backlog
  doesn't overwrite it); a live event without `se` clears what they knew. A reading-less event adds no reading.
- They draw the value card's sensor badge, warm-up and countdown ring from it (docs/FEATURES.md, "Sensor life") and
  keep `no_data` quiet while the sensor has ended or warms up (spikes/alerts/SPEC.md §2).
- No serial on the wire (the start identifies the sensor). Chunks don't carry it; the BLE companion path relays the
  same live events.

## Invite link / QR

Since Sukrara 0.11 the invite is the **v3 short link** of [PROTOCOL-identity](PROTOCOL-identity.md#short-invites-v3):
`https://sukrara.mauri.app/join#<base64url(I.pub[0:8] ‖ secret[16])>`, 63 characters, QR version 5. The secret's
base64url is the request token `t`. Expiry, relays and the wearer's profile are in the invite gift wrap, so the
follower sees who is inviting before asking.

**Single use, no code (0.11.4).** The link is a bearer token: whoever holds it can ask. So:
- The **first** valid request the owner's phone receives **claims** the invite ("first" = first received;
  `created_at` can be backdated, so it isn't used). Later requests on it are not queued: each gets an
  `invite-used` notice, and the claimed request counts them (`others`), so the owner's dialog warns "another phone
  also tried this invite".
- The owner still taps **Allow** (no code): the follower's name and picture are self-asserted, and approving sends the
  last 24 h at once, which "Remove" can't take back. The owner's own label for the invite ("who is it for?", stored
  only on the owner's phone and in the owner backup) is shown next to the requester's name.
- **Deny** sends `declined`. Approving or denying marks the invite done; it stays until it expires (24 h) so late
  requesters still get `invite-used`.
- A leak before the intended follower uses the link lets someone ask in their place with any name and picture.
  Protection: the single claim, the `invite-used` notice to the real follower (who then tells the owner), and the
  owner's Allow.

**Nearby pairing** (BLE INFO `pair: {t, x, profile}`, 10 minutes) reaches every phone in range, so it keeps the
**match code** = `int(sha256(F.pub_hex + token)) mod 10000`, zero-padded to 4 digits: the follower shows it and the **owner types it**
to allow (copy-entry beats compare-and-confirm in usability studies). The single-claim rule applies too.

## Profiles

A name and a generated picture per person, so lists show faces instead of keys. **Never published**: they travel
only inside encrypted messages (invite wrap, access request, profile update, roster, owner backup). Self-asserted:
they help people recognise each other, they authenticate nothing (that's the match code's job).

```
profile = {"name": "Alex", "avatar": {"v": 1, "c": 0, "s": "cube", "a": 45, "n": 1497950029}}
```

- `name`: up to 40 characters.
- `avatar`: the numbers that draw the picture, never an image. **`v` is the renderer version.** Every app keeps the
  renderer of every version it ever shipped, so a picture made by an older build looks the same forever. An app that
  meets a newer `v` keeps and forwards the object unchanged and draws the fallback (grey `#78909C` square with the
  name's initial) until it is updated. Receivers ignore avatar objects over 512 bytes of JSON.
- **v1** (2026-10-17, Android `core/Avatar.kt`, Mac `Avatar.swift`; drawn in unit coordinates, y down, clipped to a
  circle by the UI):
  - `c` 0–7: linear gradient from colour A to B, `a` degrees (multiple of 15) from `0.5 − (cos a, sin a)/2` to
    `0.5 + (cos a, sin a)/2`. Pairs (ARGB): `1BB5C9→0B2E5E`, `C6F432→2E7D32`, `FF8A65→BF360C`, `BA68C8→4A148C`,
    `FFD54F→E65100`, `4FC3F7→01579B`, `F06292→880E4F`, `90A4AE→263238`.
  - `s` shape in white: `cube` (three faces, w = 0.22, alpha 235/170/110), `drop` (circle r = 0.2 at (0.5, 0.62) +
    triangle to (0.5, 0.2), alpha 220), `dots` (five circles from `sha256("sukrara/avatar/v1/dots:" + n)`: bytes
    4k…4k+3 → r = 0.08 + 0.12·b/255, x = 0.25 + 0.5·b/255, y = 0.25 + 0.5·b/255, alpha = 90 + ⌊110·b/255⌋;
    vectors in [identity/avatar-v1.json](identity/avatar-v1.json)), `rings` (4 concentric strokes, r = 0.09·i,
    width 0.03, alpha 60 + 40·i), `letter` (the name's first character, uppercased, bold system font at 0.5, alpha
    240, glyph box centred).
  - `n` seed 0 … 2³¹−1 (only `dots` uses it).
- **Wearer → followers**: the invite wrap and every roster carry `profile`; a change republishes the roster at once.
- **Follower → wearer**: the access request carries `profile`; a later change is a `profile` rumor.

## Flows

**Share (follower joins)**
1. Owner taps *Share* → app makes a token, shows QR + share-sheet link.
2. Follower app (role "I follow someone") scans/opens it, generates `F`, sends an *access request* to `I` on the
   invite's relays, shows the match code, waits.
3. Owner app sees the request (checked on every chunk publish ≈ 5 min, immediately while the app is open), shows
   "Mom's phone wants to see your glucose · code 4821 — Allow / Deny".
4. Allow → owner sends a *key delivery* for the current epoch, records `F` in the follower list and the owner backup,
   and republishes the last 24 h of chunks (paced, newest hour first) so the new follower gets a complete, fresh copy.
5. Follower decrypts the delivery, then: fetch roster → backfill chunks (`#c` + `authors = roster.devices`) → follow live.

**Missing history** (Sukrara, 2026-10-16): after a full backfill the follower checks the last 12 h. Hours without a
reading while the stream is live ⇒ backfill again after 90 s (the owner may still be pacing chunks out — a key
delivery jumps ahead of queued chunks), then a *history request* (≤ 1 per 30 min). The owner answers only for an
approved, non-revoked `F` and a rumor younger than 1 h, at most once per 10 min overall, by republishing its chunks for
hours ≥ `since` (capped at 24 h) under the current epoch on the relays (and BLE). Nothing is sent back privately: the
follower's open `#c` subscription picks the chunks up. Older followers/owners ignore the unknown `type`.

**Revoke** (owner removes a follower)
1. New `G_{n+1}`; *key delivery* to every remaining follower; *revoked notice* to the removed one.
2. Publish roster with `epoch n+1`; update owner backup; switch publishing to `G_{n+1}`.
3. Republish the last 24 h of chunks under `n+1` (paced) so freshly delivered keys see recent history.
4. Followers keep older epochs' keys → their local history stays readable. A revoked follower keeps what it already
   has (no forward secrecy) but gets nothing new.

**Phone change / reinstall** (seamless for followers)
1. New phone: *Restore* → Credential Manager returns the identity `nsec` from Proton Pass / Bitwarden / Google.
2. App fetches the *owner backup* → gets `sid`, all epochs, followers, relays.
3. Generates a new `D'`; rotates to `G_{n+1}` (the old phone knew `G_n`); key deliveries to all followers; roster
   with `devices:[D']`.
4. Followers see the new roster + key and continue; events from the old `D` are no longer accepted.

**Missed rotation**: a follower that sees an unknown `gk` sends a *rekey request*; the owner app answers with the
current key if `F` is still approved.

**Lost identity key** (no backup): unrecoverable by design — followers must be re-invited. (Key-migration NIPs are
all unmerged as of 2026-10.)

## Acceptance rules (followers)

- Roster: author == `I`, newest `created_at` wins.
- Data (live/chunk): author ∈ current `roster.devices`, `gk` matches a known epoch, decrypts, `v == 2`. A chunk's `c`
  must be `H("cgm2:glucose:"+sid)` and its `d` must be `H("cgm2:h:"+sid+":"+h)` for the payload's own hour `h`.
- Unknown `gk`: a rekey request only when the event is signed by `I` or a roster device (≤ 1 per `gk` per 5 min), so a
  stranger can't make followers send them.
- Key delivery: sealed by `I`, and its `sid` equal to the one already known (if any).
- Treatments: see [Treatments](#treatments-insulin--carbs--notes--v2-addition-2026-10-16) (writer scope).
- Never trust a reading because of *where* it came from inside the app — each stored reading records `via`
  (`relay:<host>`), and the follower DB is separate from the owner role's DB.

## Backup to a password manager

Credential Manager **password** item (works with Proton Pass, Bitwarden, Google Password Manager, KeePassDX; no domain
needed; portable between managers by export/import):
`CreatePasswordRequest(id = "CGM Share · <npub I, short>", password = nsec(I))`. Restore = `GetPasswordOption`.
Optional for people who want it: NIP-49 `ncryptsec` (log_n 18, key_security 0x01) on a printable recovery sheet.
Passkeys (PRF) are **not** used: need a domain, Bitwarden Android doesn't return PRF, and passkeys can't yet move
from Proton Pass to Bitwarden (lock-in).
