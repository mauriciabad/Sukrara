# Open Glucose Stream (OGS) — draft 3

> [!WARNING]
> **Work in progress.** This specification is still being developed and can change at any time. When Sukrara 1.0
> is released, its versions will be consolidated: only the latest is kept, earlier drafts are deleted, and the
> version numbering starts again. Don't rely on "draft 3" as a stable name or number.

**An open protocol for sharing a live glucose stream, end-to-end encrypted, with the people you choose, for as long
as you choose. It runs over public Nostr relays when they're apart and over Bluetooth when they're close. There's no
account and no server, and nothing public links a stream to a person.**

`draft 3` · 2026-10-19 · status: **proposal**. No app speaks it yet. Sukrara 0.11 speaks [draft 1](../README.md).
· independent reference implementation: [`../reference`](../reference) (`ogs/d3`, Python)

Draft 3 is a clean-sheet redesign. Draft 1 described one app's wire format. Draft 3 starts from requirements and the
current state of the art ([rationale.md](rationale.md)), and breaks compatibility on purpose
([migration-from-draft1.md](migration-from-draft1.md)).

## Documents

| | |
|---|---|
| [ogs3-core.md](ogs3-core.md) | **Core**: participants and keys, epochs and the day-key tree, records, envelopes, invites, grants, membership, acceptance rules, lifecycle, versioning |
| [ogs3-data.md](ogs3-data.md) | **Data model**: glucose series with HI/LO and quality flags, sensor sessions, device status, settings, alerts and acknowledgements, insulin, carbs, fingersticks, exercise, notes; units and time; bridges to Nightscout, FHIR, Health Connect, HealthKit, Tidepool |
| [ogs3-nostr.md](ogs3-nostr.md) | **Nostr transport**: kinds, filters, relays, AUTH, retention, what never to publish |
| [ogs3-ble.md](ogs3-ble.md) | **Bluetooth LE transport**: derived service UUIDs, handshake, framing, nearby join |
| [ogs3-security.md](ogs3-security.md) | **Security and privacy**: threat model, properties, what leaks, key compromise, normative privacy rules |
| [conformance.md](conformance.md) | **Checklist** per class: reader, contributor, publisher, administrator, nearby, bridge, portable |
| [rationale.md](rationale.md) | **Requirements and decisions**: what was considered, what was chosen and why, and what the state-of-the-art review changed |
| [migration-from-draft1.md](migration-from-draft1.md) | **What changed** for apps that implemented draft 1 |
| [test-vectors/ogs-draft3.json](test-vectors/ogs-draft3.json) | **Vectors**: tree, covers, day material, blinded authors, invite, 40 events with plaintexts, a 38-step acceptance scenario, administrator checks, Bluetooth, bridges |

## The model in one screen

```
 administrator (holds I)                 epoch = random root ──GGM tree over 1024 days──▶ leaf per UTC day
   invite ─ogs:<fingerprint+secret>─▶ joiner   leaf(day) ─▶ content key (NIP-44) · d-tag key · blinding key · BLE key
   grant  = minimal cover of the agreed days   ─▶ a member can derive exactly those days: history start AND end
   remove = new epoch (rotation)               ─▶ removed members can't read or even recognise new traffic

 every record is signed by its author (I, a wearer device D, or a member M)            ← authority
 every envelope (kind 31078) is signed by author(P, lane) = P blinded per day          ← unlinkable, unforgeable
   relays see:  new pubkey each day · opaque d · expiration · ciphertext · nothing else
```

Principles:
1. **The record is the authority.** Its author signs it, and that signature travels with it on relays, Bluetooth and
   in exports. Where a copy came from earns it no trust.
2. **No public identity.** Stream keys never publish anything public, and no identifier is in the data. Keys seen
   on relays change every day.
3. **Consent has a shape.** An invite says what it offers. A grant is a range of days. Removal rotates the keys.
4. **Relays are a pipe.** Everything expires within days to weeks. Members keep their own history.
5. **Reuse before inventing.** NIP-01, 40, 42, 44, 59 (and optionally 70, 77); HMAC and HKDF. The two
   constructions OGS adds, a GGM key tree and key blinding, are textbook (Tor v3 uses the same blinding).

## Run the reference

```sh
cd spec/reference
uv run ogs d3 vectors verify        # tree, day material, authors, invite, wraps, owner checks, BLE, 38-step scenario
uv run python -m unittest discover tests
uv run ogs d3 e2e                   # live: owner + reader + writer on public relays, removal and rotation (~75 s)
```

## Status and naming

- "Open Glucose Stream" and "OGS" are working titles. Every constant is vendor-neutral.
- Kinds `31078`, `4078` and `4079` were unassigned on 2026-10-19. They are not registered yet.
- **Not a medical device.** OGS moves numbers. A follower's copy can be late. Don't base treatment decisions on it.

## Licence

**CC0-1.0** (public domain dedication), chosen by the maintainer on 2026-10-19, for the text, the test vectors and
the reference implementation, as published in the standalone repository
[open-glucose-stream](https://github.com/mauriciabad/open-glucose-stream). This folder is the working copy inside
the Sukrara research repository. See [docs/24b](../../docs/24b-open-standard-draft3.md#publishing).
