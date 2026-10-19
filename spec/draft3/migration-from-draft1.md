# Migrating from OGS draft 1 to draft 3

Draft 3 is not backwards compatible with draft 1, on purpose, and there is no compatibility mode. Draft 2 (draft 1 plus
[PROPOSALS.md](../PROPOSALS.md)) was never published as a separate document. Its open wire changes P1, P2, P7, P8,
P9 and P11 are superseded by draft 3 (table at the end). Draft-1 and draft-3 traffic can't be confused: they use
different kinds (`21078`/`21079`/`30078` versus `31078`), different labels and different invite texts. An app can
speak both during a transition, but every stream and every invite has to be created again under draft 3.

## What changed

| Area | Draft 1 | Draft 3 | What a third-party app does |
|---|---|---|---|
| Mental model | an owner phone broadcasts; followers read; writers' entries are echoed by the owner | a set of signed records; members hold time-bounded grants; every author signs its own records | re-read [ogs3-core.md](ogs3-core.md) C§1 |
| Read key | one secp256k1 key `G_n` per epoch, its `nsec` handed to followers; NIP-44 by ECDH(signer, `G`) | a 32-byte epoch root, a 1 024-day key tree, and a symmetric NIP-44 conversation key per day | replace `G` handling with C§4–C§5 |
| Grants | all-or-nothing: whoever holds `G_n` reads the whole epoch | covers: exactly the agreed days, history start and end enforced by the keys | store covers per epoch; derive leaves |
| Who signs what | `D` signs data, `I` signs the roster, writers sign treatments, all visibly | records signed by their author inside the encryption; envelopes signed by **blinded** keys that change daily | sign records (kind 4078) and envelopes (kind 31078) separately |
| Tags | `gk` epoch label, `d`/`c` from a never-rotating `sid` | `d` from the day key; no epoch label; no `sid` | drop `gk`, `c`, `sid` |
| Kinds | `21078` live, `21079` alert, `30078` chunk/roster/treatment/backup, rumors `30500`–`30502` | `31078` for everything stored; `4078` records and `4079` control rumors are never published; no ephemeral kinds | new kind numbers |
| Live readings | ephemeral `21078` per reading, plus hour chunks | the current 15-minute `cgm` block, republished per reading (addressable) | subscribe by authors; merge rows |
| Roster | `roster`: `devices`, `writers`, `relays`, `profile` under `(epoch, created_at)` | `membership`: `devices`, contributors with scopes and windows, the administrator's inbox, `caps`, under `seq` | new record type |
| Inboxes | every request to `I` (a constant key in `p` tags) | to a per-invite inbox (join) and a per-epoch administrator inbox; members choose their own inbox key | `I` never appears in a tag |
| Invite text | `https://sukrara.mauri.app/join#` + 32 characters; salt `sukrara/invite/v3` | `ogs:` + 34 characters (version byte), optional relay hints, any `https://host/…#ogs:` | new parser; the host is irrelevant |
| Match code | 4 digits, `sha256(F + token) mod 10⁴` | 6 digits, `sha256(M + ":" + token) mod 10⁶` | new code |
| Offer | none: approval gave everything | the invite carries `scopes`, `back` (history days), `days` (duration) | show it before joining |
| Writers | writers publish under their own visible key; the owner re-signs an echo; complex creator rules | contributors publish under their blinded key; no echo; entries keyed by `(by, id)` | simpler: drop echo handling |
| Revocation | rotate `G`, `revoked` notice | rotate the epoch (new tree, authors, inboxes, BLE ids); `epoch_end` in the old epoch; `ended` notice | handle `epoch_end` and `ended` |
| Time-limited access | not possible | a cover with an end day; no rotation needed | — |
| Emergency access | none | standing `auto`/`multi` invites (OPTIONAL) | — |
| Data: glucose | integer mg/dL, `r` mg/dL/min, no flags | the same units, plus row flags (`lo`, `hi`, `warmup`, `unreliable`, `calibrated`, `backfilled`, `interpolated`) and `tz` | map flags; show bounds |
| Data: history | hour chunks `[[dt, sgv, rate100]]`, first copy wins | `cgm` series (15-minute blocks, day archives), newest copy wins (corrections) | new merge |
| Data: sensor | `ss`/`se`/`sw` inside live payloads | a `sensor` record per session with `state`, `reason`, `model` | new type |
| Data: new types | — | `device` status, `settings` (unit, target, zone), `bg` (fingerstick, calibration), `exercise`, `ack` | optional to show; MUST keep |
| Data: treatments | `{id,k,a,t,n,u,del,o}`, `k` ∈ rapid/long/carbs/note, `u` in ms | separate types `insulin` (`u` = units!), `carbs` (`g`), `note` (`text`); version `rev` in ms; creator `by` | rename fields; note that `u` now means units |
| Data: alerts | `k` closed set incl. `very_high`, `extremely_high`; `state` fire/clear; `at` | open `kind`, `prio`, `state` active/cleared, `start`/`last`/`end`, `rev`, optional `text` | map: `fire` → `active`; `extremely_high` stays valid as an open value |
| Enumerations | closed (unknown → reject) | open (unknown → keep, show generically) | stop rejecting |
| Versioning | `v` per object (2, 3, 1) | no `v`: the kind and labels name the version; `caps`; new type names for breaking changes | drop `v` checks |
| BLE | constant service `7c6a0001-…`; `HELLO {nonce}` / `INFO {nonce, m}`; `INFO.i` while pairing | day GATT UUIDs, a 15-minute advertised UUID; `HELLO {nonce, since}` / `INFO {nonce, proof}`; a separate pairing service carrying the invite text | new discovery; framing unchanged |
| Profiles | `{name, avatar}` inside payloads | unchanged, but no vendor string allowed in a future renderer | — |
| Owner backup | kind 30078 signed by `I` (visible) | kind 31078 by a key derived from `I` (C§13), optional | — |

## Draft-2 proposals

| Proposal | Fate in draft 3 |
|---|---|
| P1 dedicated kinds | done differently: one addressable kind, `31078`, for everything; nothing on `30078` |
| P2 neutral invite and namespace | done: `ogs:` text, `ogs3/` labels, no host in the format |
| P3 doc fixes | done in draft 1 |
| P4 writer scope | replaced by entries keyed by `(by, id)`: a writer can't even address someone else's entry |
| P5, P6 | done in draft 1 (Mac); in draft 3 there is no `sid` to change, and every wrap carries an absolute expiration |
| P7 one version number | replaced by no `v` at all (the kind and labels are the version) |
| P8 caps | done: `caps` in the join and the membership |
| P9 open enumerations | done |
| P10 receiver checks | done: expiration on every transport; `seq` instead of `(epoch, created_at)`; `d` checked against the name |
| P11 reading corrections | done: the newest object wins |
| P12 BLE privacy | extended: no constant UUID outside pairing |
| P13 BLE limits | kept |
| P14 CGMS bridge | kept as an optional bridge (D§11.1) |
| P15 repo hygiene | done |

## For Sukrara

What moving the reference app to draft 3 costs is estimated in
[docs/24b-open-standard-draft3.md](../../docs/24b-open-standard-draft3.md#cost-of-moving-sukrara).
