# OGS — proposed changes (draft 1 → draft 2)

Found while auditing the specs against Sukrara for Android (`spikes/showcase-android`, 0.11.17) and Sukrara for Mac
(`spikes/menubar`) in investigation 24. Each item lands on Android and Mac together (parity groups `protocol-v2`,
`identity`, `treatments`). The repo has no backwards compatibility, so draft 2 replaces draft 1 outright.

**Status (2026-10-19, branch `w-security`).** The non-breaking security and robustness items are **in the apps** and
folded into draft 1: P3, P4, P5, P6, P10 (the Mac's chunk `d`/`c` check and rekey trigger only), P12, P13, P15. Still
open: P1, P2, P7, P8, P9, P11 (draft-2 wire changes, waiting for v1), the rest of P10, and P14.

Android paths are relative to `spikes/showcase-android/app/src/main/kotlin/app/mauri/sukrara/`, Mac paths to
`spikes/menubar/Sources/GlucoseBar/`.

| # | Change | Breaking | Effort |
|---|---|---|---|
| [P1](#p1) | Dedicated kinds 31078/31079/31080 instead of NIP-78's 30078 for chunks, roster, treatments | yes | S |
| [P2](#p2) | Neutral invite: any `https://<host>/join#`, the `ogs:join#` scheme, salt `ogs/invite/v1`, tag namespace `ogs1` | yes | S |
| [P3](#p3) | Fix the repo's own spec texts (match code, BLE `INFO`, backlog order, TTLs, backup fields) — **done** (match code, `INFO`, backlog order, TTLs) | no | XS |
| [P4](#p4) | Writers may only change their own entries; `o` = `follower` from every writer — **done** | no | S |
| [P5](#p5) | Mac: absolute NIP-40 expiration on every gift wrap — **done** | no | XS |
| [P6](#p6) | Mac: refuse a key delivery that changes `sid` — **done** | no | XS |
| [P7](#p7) | One version number per draft; alert `r` rounded like live | yes | XS |
| [P8](#p8) | `caps` in the roster, the access request, `HELLO` and `INFO` | no | S |
| [P9](#p9) | Unknown enum values ignored and kept, not rejected | no | XS |
| [P10](#p10) | Receivers enforce expiration, the roster epoch, and chunk `d`/`c` (Mac) — **Mac chunk `d`/`c` and rekey trigger done** | no | S |
| [P11](#p11) | A newer copy of a reading may correct an older one | no | S |
| [P12](#p12) | BLE privacy: no phone name, blinded `INFO`, check the owner before writing — **done** | yes (BLE) | M |
| [P13](#p13) | BLE limits: reassembly timeout and size cap, rate limits, no expired backlog — **done** | no | S |
| [P14](#p14) | Optional read-only CGMS (0x181F) bridge | no (new) | L |
| [P15](#p15) | Retire the outdated Python v2 owner/follower, keep `spec/reference` — **done** | no | XS |

## P1
**Dedicated kinds.** NIP-78 (`third_party/nips/78.md`) says kind 30078 is "not meant to be used as a generic
interchange format … Applications that need such interoperability should use dedicated kinds". It also says relays
SHOULD serve 30078 only to the authenticated author. A relay that follows that rule never gives chunks, the roster or
treatments to followers.

Proposal:
- chunk **31078**, roster **31079**, treatment **31080** (all addressable, all free in `nostr-protocol/registry-of-kinds`
  on 2026-10-17);
- live 21078 and alert 21079 stay, and get registered;
- the rumor kinds 30500/30501/30502 stay (they're never published) and get registered;
- the owner backup stays on 30078, because it's private data for its own author, which is NIP-78's case.

Blending in with other 30078 traffic wasn't real privacy anyway: the `gk` tag and kind 21078 already mark the stream.
Android `core/Proto.kt` (constants and filters) and `owner/OwnerEngine.kt`; Mac `NostrV2.swift`.

## P2
**Neutral invite and namespace.** A third-party app can't use `https://sukrara.mauri.app/join#` for its own users,
and the HKDF salt `sukrara/invite/v3` and tag prefix `cgm2:` carry history, not meaning.

Proposal:
- receivers accept the 32-character payload after any `https://<host>/join#` and after `ogs:join#`. The fingerprint
  authenticates the owner; the host authenticates nothing.
- `invite_key` salt `ogs/invite/v1`;
- tag namespace `ogs1`, so `H("ogs1:h:"+sid+":"+hour)` and so on;
- drop `sukrara:join#`, which no spec mentions but both apps accept (`core/Derive.kt:51-53`, `NostrV2.swift:58-67`).

Sukrara keeps `sukrara.mauri.app/join` as its landing page. Effect: every existing invite and stream would be
re-created. That's acceptable before 1.0, and the repo rule is no backwards compatibility.

## P3
**Fix the repo's spec texts.** These fix documentation only, with no wire change:
- `PROTOCOL-v2.md` says the match code is the "first 4 decimal digits of sha256". Both apps compute
  `int(sha256) mod 10000` (Android `core/Proto.kt:79`, Mac `NostrV2.swift:31-34`), as PROTOCOL-identity says.
- docs/07's `INFO` table lacks `pair.profile`.
- The backlog order is really: roster, chunks newest first, sensor, alert, treatments, live, wraps (`core/BleOwner.kt:145-189`).
- Gift-wrap TTLs are in no spec: key and revoked 30 d; request, profile, rekey, invite-used and declined 7 d; history 1 d.
- The owner backup has `writers`, and its invites have `label`, `nearby`, `sentAt`, `claimedBy` and `done` (`owner/OwnerState.kt:79-87`).
- A `clear` alert carries `th:null, n:0, tr:0` plus the last reading (`alerts/Alerts.kt:154`).
- `gk` hashes the 32 raw bytes of the key, not its hex.

*Done:* PROTOCOL-v2 now says `mod 10000` and lists the TTLs; docs/07 documents `pair.profile`, the new `HELLO`/`INFO`
(P12) and the real backlog order; ogs-ble B§6 has the order. The backup fields, the `clear` alert and `gk` were
already in ogs-nostr / ogs-data.

## P4
**Writer scope.** The owner accepts a writer's entry for **any** `id` with a higher `u`, so a writer can delete or
change the wearer's own entries (`owner/OwnerEngine.kt:378-399`, `wearer/WearerExtras.kt:134-137`).

Proposal:
- a writer may create entries, and may change or delete only entries whose first version came from that same writer
  (the owner remembers the author per `id`);
- the Mac writer sends `o:"mac"` (`Treatments.swift:20`, `Panel.swift:57`), and the owner overwrites it with
  `follower` anyway. Every writer should send `follower`.

*Done:* the owner checks the creator (`OwnerDb` keeps the first writer per `id`, its own log the wearer's entries);
followers (Android `FollowerDb.creator`, Mac `TreatmentStore.creators`) apply the same rule, ignore a writer's own copy
once the wearer's echo is there, and let the wearer's own entry replace a copy a writer forged first. The Mac sends
`o:"follower"` and offers Delete only on its own entries while it follows someone. Shared rule: `WriterScope` (Kotlin
and Swift), N§9.1.8 and N§9.2, scenario steps `treatment-writer-hijack` / `treatment-writer-edit`.

## P5
**Mac gift wraps without expiration.** `V2.gift` (`NostrV2.swift:133-142`) calls `nip59MakeGiftWrap` without extra
tags, so access requests, rekeys and history requests from the Mac stay on relays for good. Add the absolute
`expiration` the spec table gives (request 7 d, history 1 d).

*Done:* `V2.gift(…, ttl:)` adds an absolute `expiration` tag (request and rekey 7 d, history 1 d).

## P6
**`sid` must not change.** The Mac overwrites `sid` from any key delivery with `epoch ≥ max` (`NostrV2.swift:736-779`).
Android rejects a different `sid` (`follower/FollowerEngine.kt:420-493`). The spec (N§9.1) says reject, and so does
the reference scenario (step 23).

*Done:* the Mac rejects it too (`-v2selftest`).

## P7
**One version number.** Draft 1 uses `v:2` (data and messages), `v:3` (invite), and `v:1` (treatment and avatar,
where it's the renderer version and stays). Draft 2 proposal:
- every payload carries the draft's number;
- the alert `r` is rounded to 2 decimals like live (Android sends the raw double, `alerts/Alerts.kt:164-167`).

## P8
**Capabilities.** There's no way to know whether the other side supports writers, alerts, sensor life or history
requests. Add `"caps": ["alerts","sensor","treatments","writer","history"]` to the roster, the access request,
`HELLO` and `INFO`. Receivers ignore unknown caps.

## P9
**Open enumerations.** An unknown treatment `k` or alert `k` is rejected today, so adding `exercise` or
`predicted_low` would break older followers. Draft 2: keep it, show it generically, and don't merge it into rules.

## P10
**Receiver checks.**
- Followers ignore NIP-40 `expiration` and rely on relays for it, but Bluetooth backlogs replay expired events.
- Neither app checks that a roster's `epoch` matches its `gk`.
- The Mac checks neither a chunk's `d` against its `h` nor its `c` (`NostrV2.swift:675-712`), so any 30078 from a
  device is taken as a chunk.
- The Mac accepts a roster with an equal `created_at`; Android ignores it. Order by `(epoch, created_at)`, strictly.
- Android sends a rekey only for an `I`/device author, but the Mac does for **any** verified author with an unknown
  `gk`, so a stranger can make followers spam rekeys.

*Done:* the Mac checks a chunk's `c` and its `d` against `h`, and asks for a rekey only for `I` or a roster device.
Open: expiration on receivers, the roster epoch check, and ordering rosters strictly by `(epoch, created_at)`.

## P11
**Reading corrections.** Followers keep the first copy of each `t` (`follower/FollowerDb.kt:105-114`), so a chunk
that later adds a missing rate, or a corrected value, is ignored. Proposal: a reading from an event with a newer
`created_at` replaces the stored one for the same `t`.

## P12
**Bluetooth privacy.**
1. The scan response carries the phone's own Bluetooth name (`core/BleOwner.kt:83-89`). That is often a person's name,
   broadcast next to a recognisable CGM service. Drop it; followers show the profile from `INFO`.
2. `INFO.i` gives `I` to anyone in range. Send it only while pairing. Otherwise send `m = H(sid + ":" + nonce)` with a
   fresh `nonce`.
3. The Android follower reconnects to the first owner it finds without checking `INFO.i`
   (`core/BleFollower.kt`), so requests and treatments could go to a neighbour's phone. The Mac already checks
   (`main.swift:295-326`).

*Done:* no scan response; `HELLO` carries a fresh `nonce` and `INFO` answers `m = H(NS+":ble:"+sid+":"+nonce)` (the
follower's nonce rather than the owner's, so a recorded `INFO` can't be replayed; `NS` like every other derived
value), with `i`, `relays` and `pair` only while pairing. Both followers write nothing until `INFO` proves the stream,
and leave another wearer's phone (Android avoids it for 10 min while it keeps scanning). Residual: Android's GAP
service still gives the phone's name to a connected central.

## P13
**Bluetooth limits.**
- Reassembly has no timeout and no size cap on either platform. Android caps the number of open buffers at 16; the
  Mac caps nothing (`core/Ble.kt:55-73`, `BLEV2Central.swift:127-152`).
- The owner accepts writes from any central, answers `GATT_SUCCESS` before parsing, and has no rate limit.
- The backlog cache re-sends expired events.

Add the limits in ogs-ble B§4.3 and B§8, and filter the cache by `expiration`.

*Done:* reassembly drops a buffer idle 30 s and a message over 256 KiB and keeps ≤ 16 buffers (Android `Ble.Reassembler`,
Mac `BLEV2Central.Reassembler`); the Android owner checks each write before answering (malformed or over the byte
budget → `GATT_WRITE_NOT_PERMITTED` and disconnect), allows 30 messages and 256 KiB a minute per central and 8
centrals, sends one backlog per central per 5 s, and drops expired events from the cache.

## P14
**CGMS bridge** (ogs-ble Annex B). An opt-in, bonded, read-only CGMS server on the owner phone or a follower device,
so xDrip+'s GluPro collector and other CGMS clients can show the stream. Start with a PoC on a follower emulator
against xDrip+ GluPro, then decide. It must never feed insulin dosing.

## P15
**Repo hygiene.** `spikes/nostr/v2/cgm2.py` and `cli.py` still make and parse the removed v2 `cgmshare:join?` links,
and list relays the spec calls unusable (`relay.nostr.net`, `offchain.pub`). The Mac can't pair with that Python
owner. `spec/reference` now does the "independent second implementation" job. Delete the v2 CLI owner and follower,
or point them at `spec/reference`. The interop harness (`spec/reference/interop/sukrara_side.py`) still uses
`cgm2`'s primitives, which are fine.

*Done:* `v2/cli.py`, `nodes.py`, `scenarios.py` and `debug_share.py` are deleted; `cgm2.py` keeps the primitives
(no invite links, no owner/follower state) with the current relay list.

## P16
**Pen doses in draft 3.** Draft 1 carries an injection read from a connected pen as optional `pr` (primed units),
`pen` (serial) and `pd` (the doses) on an insulin treatment (D§6). Draft 3's `insulin` record (ogs3-data D§9.3) has
`how: pen` but no priming or per-dose list yet: add them (or a `doses` array) before draft 3 freezes.
