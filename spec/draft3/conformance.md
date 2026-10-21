# OGS draft 3 conformance

An implementation claims one or more **classes**. Each line is a MUST unless it says SHOULD or MAY. Section
references: C = [ogs3-core.md](ogs3-core.md), D = [ogs3-data.md](ogs3-data.md), N = [ogs3-nostr.md](ogs3-nostr.md),
B = [ogs3-ble.md](ogs3-ble.md), S = [ogs3-security.md](ogs3-security.md). Machine checks: the vectors in
[`test-vectors/ogs-draft3.json`](test-vectors/ogs-draft3.json), for any language, and `uv run ogs d3 vectors verify`
in `spec/reference`.

## Classes

| Class | For | Requires |
|---|---|---|
| **R — Reader** | a follower app, watch, widget, home automation, clinician viewer | — |
| **C — Contributor** | a carer app that adds entries or acknowledges alerts | R |
| **P — Publisher** | a wearer's own device, part of the administrator's app, that reads the sensor or enters data (never a third-party app: those are R or C) | — |
| **A — Administrator** | the app that holds the identity: invites, grants, membership, rotation | P (usually) |
| **NR / NP — Nearby reader / publisher** | the Bluetooth link | R / P |
| **X — Bridge** | export to Nightscout, FHIR, Health Connect, HealthKit, Tidepool | R or P |
| **K — Portable administrator** | can take over another app's stream from its backup | A |

## R — Reader

Crypto
- [ ] BIP-340 and NIP-01 ids; NIP-44 v2 with a given 32-byte conversation key; the official NIP-44 vectors pass
- [ ] NIP-59 unwrap; the rumor's `pubkey` equals the seal's signer (C§9.1)
- [ ] The tree, covers and leaves reproduce `tree.covers` and `tree.days[*].leaf` (C§4)
- [ ] Day material, `d` tags and blinded authors reproduce `tree.days` (C§5)

Joining
- [ ] Parses the `ogs:` and `https://…#ogs:` forms with hints; rejects other versions; reproduces `invite` (C§8.1)
- [ ] Opens the invite only with the 12-byte fingerprint and an unexpired `x`; passes `invite_checks` (C§8.3)
- [ ] Shows the inviter and the offer before joining; sends `join` with a fresh `M` and a fresh inbox; shows the
      six-digit match code (C§8.4–8.5)
- [ ] Answers AUTH only with its inbox key or the invite key (N§5)

Acceptance (run the 42-step `scenario` and reach every verdict and the final state)
- [ ] Grants only from `I`, with a valid membership for every listed epoch; grants add; grants and `ended` ordered by
      rumor time both ways (C§9.2, C§10.2)
- [ ] An ended epoch (`epoch_end` or a grant's `end`) is cut at its last day; rights come from the epoch's newest
      membership, so a removed writer or device is not recognised in the old epoch, even for records signed before
      the removal that arrive later (C§10.2, C§10.3, C§11.2)
- [ ] The envelope pipeline in the order of C§10.1, including `rewrapped`, `wrong-lane`, `wrong-day`, `d-mismatch`,
      `scope`, and not remembering unknown authors
- [ ] Merge rules: series corrections, entries by `(by, id)` and `(rev, author)`, creator-only edits, `rev`, `seq`,
      ties by record `id` (C§10.5)
- [ ] Alerts notify only when active, ≤ 10 minutes old, and new by `(id, n)` (D§8.2)
- [ ] `epoch_end` → waits for a grant, then one rekey request at most every 5 minutes (C§10.2)
- [ ] Expired envelopes dropped on every transport (C§7.3)

Data
- [ ] Integer mg/dL, `lo`/`hi` shown as bounds, unknown flag bits ignored (D§2, D§4.2)
- [ ] Unknown fields ignored; unknown types and enum values kept (D§1, D§12)
- [ ] SHOULD show the wearer's unit and target from `settings` (D§7); conversion by 18.01559 (D§2.2)
- [ ] Shows the age of the newest reading (S§9)

Privacy
- [ ] Never publishes anything public with a stream key; a fresh `M` per stream (S§10)
- [ ] SHOULD puncture its covers daily, to the start of its edit window, after any backfill (C§11.4)
- [ ] Re-subscribes with `since`; inbox `since` from the last connection, not a fixed age (N§4.1)

## C — Contributor
- [ ] Offers writing only while the membership lists it with the scope, for days inside its window (C§10.3)
- [ ] Records signed by `M`, in envelopes signed by `author(M, "r")` for the record's day, with the RECOMMENDED
      expiration (C§10.4, C§7.3)
- [ ] Edits only its own entries; keeps `id` and `type`; increases `rev`; deletes with tombstones (D§9.1)

## P — Publisher
- [ ] One record per envelope; blinded author per day and lane; `d` = `dtag(name)`; tags `d` and `expiration` only
      (plus optional `-`) (C§5–C§7)
- [ ] Padding to 256 bytes; at most 65 535 bytes; `created_at` strictly increasing per object (C§7.1–7.2)
- [ ] The current `cgm` block republished as readings arrive; series never cross midnight (D§4.3)
- [ ] No serial numbers or other identifiers; random `sess` (D§1, D§5)
- [ ] `sensor` on change and daily; `device` every 15 minutes while publishing (D§5, D§6)
- [ ] Alerts with `id`, `prio`, `state`, `n`, `rev` (D§8)
- [ ] Alerts muted by a shared treatment aren't sent; a device-local notification mute or slow-down never changes what is sent (D§8.2)
- [ ] Publishes to every relay in the membership, at least 1 s apart per relay (N§3)

## A — Administrator
- [ ] Separate `I`, publisher keys and epoch roots from a secure random generator; `start` = today − 30 (C§4.1)
- [ ] Invites with a fresh inbox, an offer and an absolute expiration; hints in the text (C§8.1–8.3, N§6)
- [ ] Single claim; replayed joins never re-admit; explicit human decision unless `auto`; a typed code for nearby
      joins (C§8.5–8.6); passes `owner_checks`
- [ ] Grants with minimal covers and each epoch's newest membership: the current epoch from `from` to `to` or its
      end; older epochs only up to their last day, with `end` (C§9.2)
- [ ] Membership on every change and daily; only contributors listed; `seq` per epoch (C§9.3)
- [ ] Rotation on removal, on device removal, on shortening a grant, before the epoch ends; SHOULD every 30 days.
      A last membership in the old epoch when a listed key was removed, then `epoch_end` (C§11.2, C§11.5)
- [ ] Deduplicates wraps; answers rekey, history (≤ 31 days) and leave per C§9.4
- [ ] Emergency invites, if offered: no scopes at all, short window, notify, rate limit (C§8.7)
- [ ] MUST NOT list in `devices`, or send epoch roots to, any key learnt through an invite, `join`, `link` or
      `manage`, or any third-party app or device; those are members only (C§11.6)

## NR / NP — Nearby
- [ ] Day GATT UUIDs and 15-minute advertised UUIDs reproduce `ble` (B§3.1); no name or other data in advertisements
- [ ] Pairing service only while nearby pairing is open (B§3.2)
- [ ] Framing reproduces `ble.frames_by_att_mtu`; reassembly limits (B§4)
- [ ] NR: verifies `INFO.proof`, then answers `AUTH`, before writing anything else (B§6)
- [ ] NP: sends no envelope and accepts nothing before a valid `AUTH`; backlog by `since`, no expired envelopes, no
      backup or invite wraps; limits (B§6, B§8)
- [ ] Nearby joins: typed code (B§7)

## X — Bridge
- [ ] Asks the user before sending anything out (S§10.6)
- [ ] Mappings as D§11, compared with `bridges` in the vectors: Nightscout 39/401 for `lo`/`hi`; FHIR LOINC 99504-3
      and comparators; skips `lo`/`hi` for Health Connect

## K — Portable administrator
- [ ] Reads and writes the C§13 backup; rotates on takeover. (No vectors yet.)

## Where implementations stand (2026-10-19)

| | Sukrara Android | Sukrara Mac | `spec/reference` (Python) |
|---|---|---|---|
| R | — (draft 1) | — (draft 1) | ✅ vectors, scenario, live run on 3 public relays (9 checks, incl. a removed writer) |
| C | — | — | ✅ live run (carbs accepted by the administrator and the other member) |
| P | — | — | ✅ builders, vectors, live run |
| A | — | — | ✅ minimal: invites, joins, grants, membership, rotation, live run; no persistence, no backup |
| NR / NP | — | — | UUIDs, both proofs and framing only (no radio) |
| X | — | — | mappings and vectors |
| K | — | — | — |
