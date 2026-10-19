# 24b — Open Glucose Stream draft 3: a clean-sheet standard

*2026-10-19 · investigation report · follows [24](24-open-standard.md) · spec: [`spec/draft3/`](../spec/draft3) ·
reference: [`spec/reference/ogs/d3`](../spec/reference/ogs/d3) · evidence:
[`evidence/24b-draft3-e2e.log`](evidence/24b-draft3-e2e.log)*

## Goal

The user, 2026-10-19: *"Do a draft 3 that defines the spec without being biased to what Sukrara had implemented. It
is fine if it includes breaking changes and deep mental-model changes. Make sure to check the state of the art again
when making it, to not forget anything important."* The privacy rule stands: no public identity linked to a glucose
stream.

## Result

| | Result |
|---|---|
| Spec set | **OGS draft 3** in `spec/draft3/`, written in RFC 2119 style, with vendor-neutral constants. Documents: core, data model, Nostr transport, Bluetooth transport, security and privacy, conformance, rationale (requirements and decisions), and a migration note from draft 1. |
| Mental model | **Draft 1:** an owner phone broadcasts with one read key per epoch. **Draft 3:** a set of records, each **signed by its author**. Members hold **grants that are ranges of days**, cut from a key tree. Relays see **keys that change every day**. |
| State of the art | Re-checked against current NIPs and open PRs, Marmot (MLS over Nostr), the HL7 CGM IG 1.0.0, Tidepool, Nightscout API v3, Open mHealth / IEEE 1752, HealthKit, Health Connect, Bluetooth CGMS, the consent models of Dexcom Follow, LibreLinkUp and Nightscout, MLS / Signal / Megolm, and BLE platform limits. What changed the design: [below](#what-the-state-of-the-art-changed). |
| Reference | `spec/reference/ogs/d3`, Python with standard-library crypto (the draft-1 primitives plus HMAC trees and secp256k1 key tweaking). **MLS was not chosen**, so no heavy library is needed. Parts: owner/administrator, member, BLE identifiers, bridges, vectors, live run. |
| Test vectors | `spec/draft3/test-vectors/ogs-draft3.json`, byte-for-byte reproducible: <br>• the tree: 2 epochs, 5 covers, day material, blinded authors and a blinded secret, `d` tags, a punctured cover; <br>• the invite in 3 text forms, 3 invite checks; <br>• **40 events**, with the plaintext of every wrap; <br>• 3 administrator checks; <br>• a **38-step acceptance scenario** with its final state; <br>• BLE UUIDs, proof, and frames at 3 MTUs; <br>• bridge examples (Nightscout, FHIR, Health Connect, Tidepool). |
| Tests | 29 offline tests: the 17 from draft 1 and 12 new ones (cover exactness and minimality on 60 random ranges, bounded grants, puncturing, blinded secret ↔ blinded public key, invite forms, validation, padding, BLE UUIDs, vectors and determinism). All pass. |
| Live run | `uv run ogs d3 e2e` against **nos.lol, relay.primal.net and nostr.mom**, throwaway keys and synthetic glucose, everything expiring within the hour. The flow: <br>1. an owner invites a reader F1 (one day back) and a writer F2 (today only); <br>2. both join by typed code and backfill **exactly** their windows; <br>3. a live update reaches both; <br>4. F2's carbs are accepted by the owner and by F1; <br>5. the owner removes F1: rotation, F1 ended, F2 granted the new epoch; <br>6. new-epoch data reaches F2, and F1 can't read it. <br>**8/8 checks pass, in about 65 s and 3.5 s of CPU.** |
| Live run found bugs | The first run found real protocol issues, now fixed in the spec and the reference. See [Lessons from the live run](#lessons-from-the-live-run). |
| Publishing | Nothing was published. The package is ready: the site page, a standalone-repo README, and the steps. [Below](#publishing). No PR to nostr-protocol/nips. |

## Requirements

Fifteen requirements, R1–R15, are listed in full in [rationale.md §1](../spec/draft3/rationale.md#1-requirements):
- **R1 Actors:** the wearer, or a parent as administrator; several wearer devices; family; carers who write and
  acknowledge; time-limited school staff; clinicians who need history only; emergency responders; watch apps;
  integrations.
- **R2 Privacy:** no identity, no identifiers, unlinkable over time, readers hidden from each other.
- **R3 Informed, bounded consent.**
- **R4 End-to-end security** over untrusted transports.
- **R5 Offline and nearby.**
- **R6 History, corrections, 90 days for clinicians.**
- **R7 Multi-device on both sides.**
- **R8 Revocation that also stops recognition.**
- **R9 Emergency access.**
- **R10 A full CGM data model**; pump and AID left out.
- **R11 Units and time.**
- **R12 Extensibility.**
- **R13 Bridges.**
- **R14 Implementable** on phones, watches and in Python.
- **R15 No server.**

The threat model is in [ogs3-security S§1](../spec/draft3/ogs3-security.md#s1-assets-and-adversaries).

## Decisions

The full reasoning, with what was considered and why each option was rejected, is in
[rationale.md §2](../spec/draft3/rationale.md#2-decisions). In short:

1. **The signed record is the authority** (D1).
   - Every record is a NIP-01 event signed by its author inside the encryption, on every copy.
   - Contributors publish directly. There is no echo and no re-signing, which removes draft 1's hardest rule
     (P4 writer scope).
2. **A GGM day-key tree instead of one epoch key** (D2).
   - A grant is the minimal cover of exactly the agreed days. That enforces the **history start and the end of
     access with keys**: a 3-day school-trip grant ends by itself.
   - Removal rotates the epoch.
   - Members puncture old days for forward secrecy.
   - A 30-day rotation gives post-compromise security.
   - **Rejected:** MLS/Marmot (ordered commits over lossy relays; a heavy stack; account-bound KeyPackages), hash
     ratchets (no end), per-recipient ciphertexts (a `p` tag per follower).
3. **Blinded per-day authors** (D3).
   - Each publisher key is tweaked by a secret per day and lane, as in Tor v3 key blinding. Relays see unrelated keys
     every day.
   - Only the publisher can sign, and every member can compute the key to subscribe. So replacement and spam
     filtering keep working, and members can't overwrite each other.
   - **Rejected:** Marmot's one-time key per event.
4. **One addressable kind (31078) and no ephemeral events** (D4).
   - The live channel is the current 15-minute block, republished on each reading.
   - Lanes (`g`, `r`, `m`) let a member subscribe to glucose, other records or membership separately.
5. **Days as the access unit** (D5): hours would multiply the number of authors.
6. **Rotating inboxes** (D6).
   - A fresh inbox per invite and per epoch; members choose their own.
   - `I` and member keys never appear outside encryption.
7. **Invites that say what they offer** (D7).
   - The `ogs:` text has a version byte and optional relay hints, and works from any host.
   - The offer inside gives scopes, history days and duration.
   - The match code is 6 digits, typed for nearby joins.
8. **Entries keyed by `(creator, id)`** (D8): a writer can't even address someone else's entry.
9. **The membership lists only contributors** (D9): readers stay hidden from each other.
10. **Data model** (D10).
    - **Units:** integer mg/dL, converted at 18.01559 (Nightscout/Tidepool/xDrip+), with the 18.0 users documented.
    - **Readings:** row flags for `lo`/`hi`/`warmup`/`unreliable`/`calibrated`/`backfilled`/`interpolated`, and a
      `tz` offset.
    - **Records:** sensor-session records with a lifecycle and still no serial; `device` status; `settings` (unit,
      target, zone); typed entries (`insulin`, `carbs`, `bg`, `exercise`, `note`).
    - **Alerts:** an open `kind`, a Tidepool-style `prio`, a fallback `text`, and **acknowledgements between
      carers**.
    - Open enumerations, and no `v`.
11. **Bluetooth with no constant identifier** (D11).
    - Advertised UUIDs come from the day key and rotate every 15 minutes; GATT UUIDs change per day.
    - A constant pairing service exists only while pairing in person.
    - No bonding.
12. **Emergency access as a standing invite** (D12): auto-approved, read-only, short, always notifying, OPTIONAL.
13. **Short relay retention** (D13): 2 days for live blocks, 30 days for archives and entries.
14. **Publishing** (D14): a standalone repository; no nips PR.

## Draft 1 → draft 3

| | Draft 1 | Draft 3 |
|---|---|---|
| What it describes | Sukrara 0.11's wire format | a protocol from requirements; no app yet |
| Authority | the visible event author; the owner echoes writers' entries | the record signature inside the encryption; no echo |
| Encryption key | one secp256k1 `G_n` per epoch, `nsec` handed out, NIP-44 by ECDH | 32-byte epoch root → day-key tree → symmetric NIP-44 key per day |
| Access granularity | all of an epoch | any range of days: history start and end enforced |
| Time-limited access | no (needs a rotation, with the owner online) | yes, without any action |
| Forward secrecy | none | members puncture past days |
| Post-compromise security | on rotation only | scheduled rotation (30 d), plus on removal |
| Authors on relays | `D`, `I`, writers: constant, linkable forever | blinded per day and lane: unlinkable across days |
| Opaque tags | `d`/`c`/`gk` from a never-rotating `sid` | `d` from the day key; nothing constant |
| Removed follower | can still recognise the stream (knows `sid`, BLE `m`) | can't recognise anything after rotation |
| Requests addressed to | `I` (a constant `p` tag) | a per-invite and per-epoch inbox |
| Kinds | `21078`, `21079`, `30078` (NIP-78: relays may refuse to serve), rumors `30500`–`30502` | `31078` only; `4078` and `4079` never published |
| Live | an ephemeral event per reading plus hour chunks | the current block republished |
| Invite | `https://sukrara.mauri.app/join#…`, no offer, 4-digit code | `ogs:…` with a version and hints, any host, an offer, a 6-digit code |
| Consent | approve = everything, forever | approve = the offer: scopes, history days, duration |
| Writers | visible keys, echo, first-seen creator rules | contributors under blinded keys; entries keyed by creator |
| Multi-device | one owner device; each follower device a separate join | several publisher devices; `link` for a member's other devices |
| Delegated admin | no | `manage` scope (a second parent) |
| Emergency access | no | standing invites (OPTIONAL) |
| Readers visible to members | writers listed | only contributors listed |
| HI/LO, quality | no | row flags |
| Calibration, fingersticks | no | `bg` |
| Sensor | `ss`/`se`/`sw` fields | `sensor` session records with state and reason |
| Device status, settings | no | `device`, `settings` |
| Treatments | 4 kinds in one shape | typed entries; exercise added |
| Alerts | closed set; fire/clear | open kinds, priorities, fallback text, **acks** |
| Time zone | none | `tz` offset, `zone` name |
| Enumerations, versions | closed; `v` per object | open; caps; kind and labels are the version |
| Corrections | first copy wins | newest object wins |
| BLE discovery | one constant service UUID: a public health inference | day UUIDs, 15-minute advertised UUID, pairing UUID only while pairing |
| Bridges | Nightscout, Tidepool (informative) | Nightscout, FHIR CGM IG, Health Connect, HealthKit, Tidepool, Open mHealth, CGMS; FHIR summary LOINCs |

## What the state of the art changed

The full list with sources is in [rationale.md §3](../spec/draft3/rationale.md#3-what-the-state-of-the-art-review-changed).
These findings changed the design:
- **NIP-78 and `registry-of-kinds`:** relays SHOULD serve 30078 only to its author → no 30078 anywhere.
- **NIP-DA (nips#2411):** a symmetric key used directly as the NIP-44 conversation key → day keys, and no `nsec` of
  a group key.
- **Marmot:** one-time signing keys per event, and pages of rules for out-of-order commits → blinded keys instead,
  and confirmation that MLS doesn't fit lossy relays and one writer.
- **Signal sender keys, Megolm, NIP-171 epoch tickets:** ratchets give a start but no end → the tree.
- **NIP-TPLD and NIP-101h:** a `p` tag per recipient, and health kinds with plaintext tags → what not to do; those
  kinds are avoided.
- **CGMS, Tidepool and Nightscout:**
  - HI/LO as status, not a magic number → flags;
  - Tidepool's alert priority and acknowledged time → `prio` and `ack`;
  - `backfilled` and time-zone offsets everywhere → kept.
- **Conversion constants differ between projects** (18.01559 / 18.0156 / 18.0) → one is fixed, and the others are
  documented.
- **HL7 CGM IG 1.0.0:**
  - LOINC 99504-3 for readings, and summary LOINCs;
  - no treatments, trend or quality;
  - → the FHIR bridge, with summaries computed by members.
- **Health Connect has no insulin record** → the bridge table says so.
- **Dexcom, LibreLinkUp and Nightscout roles:** invite expiry, pause, a `careportal` write role, and nothing for
  history limits, time limits or emergencies → scopes, covers, standing invites.
- **iOS overflow-area advertising; no phone API for Encrypted Advertising Data** → derived 128-bit UUIDs, and
  documented platform limits.
- **NIP-44 now allows over 64 KiB** → still capped at 65 535 bytes for older libraries.
- **NIP-59 kind 21059** → not used. **NIP-70 / NIP-77 / NIP-62** → OPTIONAL hooks.

## Lessons from the live run

The first run against public relays failed 2 of 7 checks, and every failure was a real protocol gap that the offline
scenario hadn't covered:
1. **Replayed joins re-admitted a removed member.** Relays delivered the same join wrap again after a
   resubscription. The administrator approved it again, and the removed F1 got a grant for the new epoch.
   - **Spec (C§8.6, C§9.4):** deduplicate wraps by id. A join from an existing member never changes anything. A
     replayed join MUST NOT re-admit.
   - **Members (C§10.2):** ignore a grant older than the last `ended`.
2. **Same-second replacements were refused** ("replaced: have newer event"). Two membership versions were published
   in one second → `created_at` MUST strictly increase per object; publishers bump it (C§7.1).
3. **Rekey storm.** An `epoch_end` processed before the grant triggered a rekey. Duplicates multiplied it → members
   wait up to 2 minutes for the grant before asking (C§10.2).
4. **History scope worked as designed.** F2, offered "today only", correctly got nothing from before UTC midnight.
   The check in the test was wrong, not the protocol.

## Cost of moving Sukrara

Estimated in agent-days, with the repo's parity rules: Android and Mac in the same round. Today's protocol code is
about 4 900 lines on Android (`core/Proto.kt`, `Derive.kt`, `Ble*.kt`, `owner/`, `follower/`, `wearer/`,
`treatments/`, `alerts/` wire parts) and 2 600 on the Mac (`NostrV2.swift`, `BLEV2Central.swift`, `Treatments.swift`,
the alert wire parts).

| Work | Android | Mac |
|---|---|---|
| **Crypto core:** tree, covers, puncturing, day material, key tweaking. Needs secp256k1 tweak-add: rust-nostr's bindings may not expose it; secp256k1-kmp (ACINQ) / secp256k1.swift do. | 1.5 | 1.5 |
| **Records and envelopes:** control messages, invite parser, match code; driven by the draft-3 vectors | 1.5 | 1 |
| **Owner/administrator:** membership, grants, invites with offers, rotation, epoch roots in the backup, emergency invites (`OwnerEngine` rewrite) | 4 | — (the Mac isn't an owner) |
| **Member/follower engine:** acceptance pipeline, merge rules, author index and resubscription, `tick` | 2.5 | 2.5 |
| **Data model:** flags (HI/LO display in **one graph everywhere**: the `graph` parity group), sensor records, `device`, `settings` unit/target, typed entries, open alert kinds | 2.5 | 2 |
| **Bluetooth:** derived UUIDs, midnight re-registration, scan-filter refresh, pairing service | 2 | 1.5 |
| **UI:** offer picker (history, duration, scopes), member list with windows, emergency invite, ack button; strings en/es | 3 | 1.5 |
| **Tests and specs:** vectors in `InviteTest`/`BleTest`/new `Ogs3VectorsTest`, `-v3selftest`; replace `PROTOCOL-v2.md`/`PROTOCOL-identity.md` by a pointer to `spec/draft3`; parity groups; FEATURES.md, QA.md | 2 | 1.5 |
| **Total** | **≈ 19** | **≈ 11.5** |

- **About 30 agent-days in all.** With two agents per platform, that's roughly three calendar weeks, plus device QA
  by the user.
- **No compatibility:** every follower must be invited again after the update, and the release notes must say so.
- The alert *rules* and their scenarios don't change. Only the alert wire format does.
- The Nightscout, Health Connect and xDrip+ bridges get the flags for free.

## Not done / open

- **No app speaks draft 3.** The proof is the reference against its own vectors and the live run. A second,
  independent implementation (Kotlin or Swift) is the next real test.
- **Bluetooth over the air** was not exercised, only identifiers, proof and framing. The iOS background behaviour
  (B§3.3) is from Apple's documentation, not measured.
- **The owner backup** (C§13) has no vectors and no implementation.
- **Push for iOS followers** (N§9), **pump/AID records**, **cover traffic** and **kind registration** remain open.
- **The CGMS bridge octet order** is still unchecked (carried over from draft 1).
- Some state-of-the-art facts were marked unverified by the research and are worded as such in the spec: the NIP-77
  relay share, HealthKit's exact molar mass, and the CGMS bit positions.

## Publishing

**Nothing has been published, and no PR to nostr-protocol/nips will be made** (the user's decision). The steps that
remain are the maintainer's.

Material:
- [`spec/drafts/site-page.md`](../spec/drafts/site-page.md): the page for sukrara.mauri.app, updated for draft 3;
- [`spec/drafts/standalone-README.md`](../spec/drafts/standalone-README.md): the README of the spec repository;
- [`spec/drafts/outreach.md`](../spec/drafts/outreach.md): texts for other projects, still unsent.
  [`nip-pr.md`](../spec/drafts/nip-pr.md) is marked "not to be sent".

**Licence: CC0-1.0**, chosen by the user on 2026-10-19, for the text, the test vectors and the reference
implementation. The trade-off that was weighed:
- **CC0** lets anyone, including closed apps and other standards bodies, copy it with no conditions, and gives up
  the right to require credit.
- **CC-BY-4.0** would require attribution, which is a little more friction for vendors and for inclusion in other
  specs.

The reference code is CC0 too in the standalone repository: nothing requires GPL there, because the author owns all
of it and it imports no GPL code. In this repository the same files keep their GPL header, as the repo rule asks.

What is done (2026-10-19):
1. **The standalone repository is prepared locally**, not pushed, in
   `/Users/blue/Projects/cgm-reading-mac-spike/tmp/open-glucose-stream/` (git-ignored here), committed and tagged
   `draft-3`. It contains:
   - `spec/` from `spec/draft3`, with links into this repository pinned to a commit;
   - `test-vectors/`;
   - `reference/`: the draft-3 package and the shared crypto, CC0 headers, its own crypto tests;
   - `README.md` from `spec/drafts/standalone-README.md`;
   - `LICENSE`: the official CC0-1.0 legal code;
   - a CI workflow that runs the vector check and the tests.
2. **The website page** is `docs/ogs.md`, linked from `docs/index.md`. It goes live when the branch is merged into
   main.

Left for the maintainer:
1. Create `github.com/mauriciabad/open-glucose-stream`, then push the local repository and its tag.
2. Optionally, once a second implementation exists, register kinds 31078, 4078 and 4079 in
   `nostr-protocol/registry-of-kinds`. That is a registry entry, not a NIP.
3. Send the outreach texts from a project account (never from stream keys), when the user decides.

## In Sukrara

**Nothing was added to the app.** This is a standalone investigation. Moving to draft 3 is a separate decision; the
cost is above. Draft 1 stays the description of what Sukrara 0.11 speaks.

## Files

| Path | What |
|---|---|
| `spec/draft3/` | README, core, data, Nostr, BLE, security, conformance, rationale, migration |
| `spec/draft3/test-vectors/ogs-draft3.json` | vectors (throwaway keys from public strings, synthetic data) |
| `spec/reference/ogs/d3/` | `core.py` (tree, day material, blinding, records, envelopes), `data.py`, `control.py`, `member.py`, `owner.py`, `ble.py`, `bridges.py`, `vectors.py`, `e2e.py` |
| `spec/reference/tests/test_ogs3.py` | 12 offline tests |
| `docs/evidence/24b-draft3-e2e.log` | the live run on public relays |
| `spec/drafts/site-page.md`, `standalone-README.md` | publishing drafts |
