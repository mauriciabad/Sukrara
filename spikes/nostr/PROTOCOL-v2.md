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
| **live** | 21078 | `D` | `["gk", gk_n]`, exp now+1 h | `{"v":2,"t":unix_s,"sgv":mgdl,"r":rate|null,"dev":src}` → `G_n` |
| **hour chunk** | 30078 | `D` | `["d", H("cgm2:h:"+sid+":"+hourStart)]`, `["c", H("cgm2:glucose:"+sid)]`, `["gk", gk_n]`, exp hour+7 d | `{"v":2,"h":hourStart,"dev":src,"r":[[dt,sgv,rate100],…]}` → `G_n` |
| **roster** | 30078 | `I` | `["d", H("cgm2:roster:"+sid)]`, `["gk", gk_n]`, exp now+30 d (refreshed weekly) | `{"v":2,"epoch":n,"devices":[hex D…],"writers":[hex W…],"relays":[…]}` → `G_n` |
| **owner backup** | 30078 | `I` | `["d", H("cgm2:backup:"+I.pub_hex)]`, exp now+90 d (refreshed weekly) | `{"v":2,"sid","epochs":[{"n","g":nsec}],"followers":[{"f":hex,"name","since","revoked"?}],"invites":[{"t","x"}],"relays":[…]}` → **`I` itself** |
| **access request** | rumor kind 30500 in NIP-59 gift wrap (1059) to `I` | seal by `F` | — | `{"v":2,"type":"request","token":t,"name":"Mom's phone"}` |
| **key delivery** | rumor kind 30501 in gift wrap to `F` | seal by `I` | — | `{"v":2,"type":"key","sid","epoch":n,"g":nsec(G_n),"devices":[…],"relays":[…]}` |
| **rekey request** | rumor kind 30500 in gift wrap to `I` | seal by `F` | — | `{"v":2,"type":"rekey","have":gk}` (follower saw an unknown `gk`) |
| **revoked notice** | rumor kind 30501 in gift wrap to `F` | seal by `I` | — | `{"v":2,"type":"revoked"}` (courtesy; security comes from rotation) |

Gift wraps (kind 1059) are signed by a random one-time key and `p`-tag the recipient; the **seal's signer** is the
authenticated sender. Followers accept a key delivery **only if the seal is signed by `I`**; the owner accepts a
request only with a valid, unexpired invite token (and the owner still confirms it in the UI).

### Relay rules (learned while testing, 2026-10-16)

- **NIP-42 AUTH**: some relays (relay.nostr.net, relay.damus.io) only serve kind 1059 `#p` subscriptions to the
  recipient. Answer `["AUTH", challenge]` with a kind 22242 event signed by the key the wraps are addressed to
  (owner → `I`, follower → `F`) and re-issue the REQs. nos.lol, relay.primal.net, nostr.mom serve wraps without AUTH.
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
- `o` is kept as created: `phone`, `mac` (legacy-link entries) or `follower` (writer entries). Deletes are the same
  event with `"del":true` (tombstone, never dropped from the log).

**Owner** (the wearer's phone) publishes its **whole merged log** signed by its device key `D` (the key in
`roster.devices`, the one that signs live/chunks): its own entries, the Mac's (legacy link), accepted writer
entries, deletes.
- On every change of the log: that entry, at once (CONTROL lane, paced ≥ 2 s per relay, replaced by `d` in the queue).
- **Republish** every entry with `t` ≥ now − 30 d (tombstones too), paced: on an **epoch rotation** (encrypted to the
  new `G`), when a **new follower is approved**, and at least **weekly** (keeps the 30-day expiry rolling).
- BLE: every treatment event is pushed to subscribed centrals like any publish; the `HELLO` backlog also carries the
  treatment events of the last 30 days (newest 200), after the chunks and before live.
- Writer events stay accepted only if the author ∈ roster `writers` ∧ active follower (docs/07), then merged (§1)
  into the log → which re-publishes them signed by `D` (**the echo**).

**Followers** (Android + Mac) receive treatments:
- `REQ {"kinds":[30078], "#c":[H("cgm2:treatments:"+sid)], "authors": roster.devices ∪ roster.writers,
  "since": now − 30 d}`; re-issued when the roster's devices or writers change. Over BLE they arrive unasked.
- Accept iff: valid signature; author ∈ current `roster.devices ∪ roster.writers`; `gk` names a known epoch
  (unknown `gk` from a device ⇒ rekey request, as for chunks); NIP-44 decrypt with `(G_n.sec, author.pub)`;
  `d` == `H("cgm2:tr:"+sid+":"+id)`; `k` ∈ `rapid|long|carbs|note`.
- Merge with SPEC §1 (newest `(u, o)` wins), persist in the follower's own DB, draw them, serve them from the
  follower's Nightscout API (`/api/v1/treatments`, SPEC §5 mapping).

**Writers** (followers in roster `writers`):
- Show *Add entry* only while the follower's own key `F` is in the newest roster's `writers` (live: a roster without
  it hides the form at once; otherwise "*<wearer> hasn't allowed you to add entries*").
- Per entry the writer sent: *sending* → *sent* (a relay `OK true` or the BLE write response) → **accepted** when an
  event for the same `id` signed by a **roster device key** arrives with `u ≥` the sent `u` (the echo). No echo after
  ~2 min ⇒ shown as *not accepted yet* (keeps listening; the owner may be offline). There is no explicit NACK:
  a rejected entry simply never echoes.

## Invite link / QR (no secrets)

```
cgmshare:join?i=<npub I>&t=<invite token, 16 random bytes b64url>&x=<expiry unix s>&r=<relay>,<relay>
```
Leaking it gives nobody data: it only lets someone *ask*; the owner must approve. Tokens are single-use and expire
(default 24 h). Both screens show a **match code** = first 4 decimal digits of `sha256(F.pub_hex + token)` so the
owner can confirm "it's really Mom's phone".

## Flows

**Share (follower joins)**
1. Owner taps *Share* → app makes a token, shows QR + share-sheet link.
2. Follower app (role "I follow someone") scans/opens it, generates `F`, sends an *access request* to `I` on the
   invite's relays, shows the match code, waits.
3. Owner app sees the request (checked on every chunk publish ≈ 5 min, immediately while the app is open), shows
   "Mom's phone wants to see your glucose · code 4821 — Allow / Deny".
4. Allow → owner sends a *key delivery* for the current epoch, records `F` in the follower list and the owner backup.
5. Follower decrypts the delivery, then: fetch roster → backfill chunks (`#c` + `authors = roster.devices`) → follow live.

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
- Data (live/chunk): author ∈ current `roster.devices`, `gk` matches a known epoch, decrypts, `v == 2`.
- Never trust a reading because of *where* it came from inside the app — each stored reading records `via`
  (`relay:<host>`), and the follower DB is separate from the owner role's DB.

## Backup to a password manager

Credential Manager **password** item (works with Proton Pass, Bitwarden, Google Password Manager, KeePassDX; no domain
needed; portable between managers by export/import):
`CreatePasswordRequest(id = "CGM Share · <npub I, short>", password = nsec(I))`. Restore = `GetPasswordOption`.
Optional for people who want it: NIP-49 `ncryptsec` (log_n 18, key_security 0x01) on a printable recovery sheet.
Passkeys (PRF) are **not** used: need a domain, Bitwarden Android doesn't return PRF, and passkeys can't yet move
from Proton Pass to Bitwarden (lock-in).
