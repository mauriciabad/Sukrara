# Open Glucose Stream (OGS)

**An open protocol for sharing a live glucose stream, end-to-end encrypted, with the people you choose, for as long
as you choose. It runs over public Nostr relays and over Bluetooth LE. There's no account and no server, and nothing
public links a stream to a person.**

Status: **draft 3** (2026-10-19), a proposal. Feedback is welcome in the issues.

## Why

Someone wearing a continuous glucose monitor (CGM) who wants family to see their glucose today has two choices:
- the manufacturer's cloud (Dexcom Follow, LibreLinkUp), tied to one app and one company;
- a self-hosted Nightscout, a server with a password that sees everything in plaintext.

OGS is a third way:
- the data is encrypted on the wearer's phone;
- any relay carries it, and Bluetooth carries it with no internet;
- access is a range of days the wearer agreed to, enforced by the keys;
- any app can take part: as a reader, as a carer adding insulin and carbs, as a publisher reading the sensor, or as
  a bridge to Nightscout, FHIR, Health Connect or HealthKit.

## How it works

- A stream is a set of **records**, each signed by whoever wrote it: the wearer's devices or an approved carer.
- Records travel in **envelopes** (Nostr kind 31078), encrypted with a key for one UTC day.
- The day keys are the leaves of a **binary key tree** (1 024 days per epoch). Giving someone access means giving
  them the few tree nodes that cover exactly the agreed days. History start and end are enforced cryptographically,
  so access can end without anyone being online.
- Envelopes are signed by keys **blinded per day**. Relays see different, unrelated keys every day, but only the real
  publisher can sign.
- Joining takes an `ogs:` invite (a QR code or a link), an offer the joiner can read, a six-digit code and the
  wearer's Allow. Removing someone starts a new epoch.

Reused: NIP-01, NIP-40, NIP-42, NIP-44 v2, NIP-59 (optionally NIP-70 and NIP-77); HMAC and HKDF. No new primitives.

## Contents

| | |
|---|---|
| [spec/README.md](spec/README.md) | overview and the model in one screen |
| [spec/ogs3-core.md](spec/ogs3-core.md) | keys, epochs, key tree, records, envelopes, invites, grants, acceptance rules, lifecycle |
| [spec/ogs3-data.md](spec/ogs3-data.md) | the data model and bridges |
| [spec/ogs3-nostr.md](spec/ogs3-nostr.md) | Nostr transport |
| [spec/ogs3-ble.md](spec/ogs3-ble.md) | Bluetooth LE transport |
| [spec/ogs3-security.md](spec/ogs3-security.md) | threat model and privacy rules |
| [spec/conformance.md](spec/conformance.md) | checklists per implementation class |
| [spec/rationale.md](spec/rationale.md) | requirements, decisions, state of the art |
| [spec/migration-from-draft1.md](spec/migration-from-draft1.md) | changes since draft 1 |
| [test-vectors/ogs-draft3.json](test-vectors/ogs-draft3.json) | deterministic vectors and a 38-step acceptance scenario |
| [reference/](reference) | independent Python implementation (standard-library crypto) |

## Try the reference

```sh
cd reference
uv run ogs d3 vectors verify
uv run python -m unittest discover tests
uv run ogs d3 e2e        # a live run on public relays with throwaway keys and synthetic glucose
```

## Implementations

| | Classes | Status |
|---|---|---|
| reference (this repository, Python) | reader, contributor, publisher, administrator | vectors, live run |
| [Sukrara](https://sukrara.mauri.app) (Android, macOS) | — | speaks draft 1; draft 3 planned |

Add yours with a pull request.

## Not a medical device

OGS moves numbers. Nothing in it is validated for treatment decisions, and a follower's copy can be late.

## Licence

Specification text and test vectors: `<LICENCE>`. Reference implementation: see `reference/LICENSE`.
