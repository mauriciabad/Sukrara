# Open Glucose Stream (OGS)

> [!WARNING]
> **Work in progress.** This specification is still being developed and can change at any time. When Sukrara 1.0
> is released, its versions will be consolidated: only the latest is kept, earlier drafts are deleted, and the
> version numbering starts again. Don't rely on "draft 3" as a stable name or number.

**An open protocol for sharing a live glucose stream, end-to-end encrypted, with the people you choose. It runs over
public Nostr relays when they're apart and over Bluetooth when they're close. There's no account and no server.**

`draft 1` (wire version 2) · 2026-10-18 · reference implementation: [Sukrara](https://sukrara.mauri.app) (Android
and Mac) · second, independent implementation: [`reference/`](reference) (Python)

Today a CGM wearer who wants family to see their glucose has two choices. One is the manufacturer's cloud
(LibreLinkUp, Dexcom Follow), tied to one app and one company. The other is self-hosting Nightscout, a server with a
password that sees everything in plaintext. OGS is a third way:
- the data is encrypted on the wearer's phone and readable only by approved followers;
- any relay carries it, and Bluetooth carries it with no internet at all;
- any app can join: as a follower (a viewer, widget, watch or Home Assistant), as a writer (adding insulin and carbs),
  or as the owner app that reads the sensor.

> **Draft 3** ([`draft3/`](draft3), 2026-10-19) is a clean-sheet redesign: grants as ranges of days from a key tree,
> records signed by their authors, keys blinded per day, a fuller data model, and no constant identifier on relays or
> over Bluetooth. No app speaks it yet. This page and the documents below are **draft 1**, which Sukrara 0.11 speaks.
> Report: [docs/24b](../docs/24b-open-standard-draft3.md).

## Documents

| | |
|---|---|
| [ogs-data.md](ogs-data.md) | **Data model**: readings, hour chunks, sensor life, treatments, alerts, profiles, trend, Nightscout/Tidepool mapping, versioning |
| [ogs-nostr.md](ogs-nostr.md) | **Nostr transport**, written as a NIP: keys, kinds, tags, encryption, invite and consent, acceptance rules, lifecycle, privacy and security, test vectors |
| [ogs-ble.md](ogs-ble.md) | **Bluetooth LE transport**: GATT service, framing, session, nearby pairing, limits. Annexes: NIP-BE, and the Bluetooth SIG CGM Service (0x181F) as an optional bridge |
| [conformance.md](conformance.md) | **Checklist** per class (follower, writer, owner, Bluetooth, portable owner, CGMS bridge), and where the apps stand |
| [PROPOSALS.md](PROPOSALS.md) | **Changes for draft 2**: what Sukrara should change before anyone else ships (P1–P15) |
| [test-vectors/ogs-draft1.json](test-vectors/ogs-draft1.json) | **Vectors**: throwaway keys, derivations, the invite, 30 events with their plaintexts, a 27-step acceptance scenario, Bluetooth frames and `INFO`, CGMS packets |
| [reference/](reference) | **Reference tool**: standard-library crypto, a follower, vector generation and checking, a live relay follower, and the interop harness |
| [drafts/](drafts) | **Unsent texts**: the NIP pull request, the web page, and messages to other projects. Publishing is the maintainer's call. |

## Design in one screen

```
 wearer's phone (owner)                                                    follower apps
  I  identity: signs roster + messages to followers, seals invites   ─┐
  D  device:   signs live, alerts, chunks, treatment log              ├─ NIP-44 to G ─▶ relays / BLE ─▶ verify, decrypt
  G  read key: one encryption per event for every follower           │                                 (roster says who
  sid:          derives opaque tags (only followers can recognise)   ─┘                                  may publish)
 join:   link (fingerprint of I + secret) → invite gift wrap → access request → human Allow → key delivery (gift wrap)
 remove: rotate G → new key to the others, "revoked" to the removed one
```

Principles:
1. **The same signed bytes on every transport.** Trust comes from signatures and the roster, never from the path.
2. **No public identity on the stream.** The protocol is public, so a stream is recognisable. Its keys must never
   carry a name.
3. **Consent is explicit and revocable.** Each follower is approved by a human. Removing one rotates the key.
4. **Relays are a pipe, not a database.** Everything expires: live in 1 h, chunks in 7 d, treatments in 30 d. Each
   follower keeps its own history.
5. **Reuse before inventing.** NIP-01, 40, 42, 44 and 59; the Nightscout trend directions; mg/dL as Nightscout,
   LibreView and CGMS use it. Nothing new is cryptographic.

## Status and naming

- *Draft 1* describes what Sukrara 0.11 sends today, so other apps can interoperate now. The non-breaking fixes
  P3–P6, P10 (in part), P12, P13 and P15 are in the apps and in draft 1 (P12 changed the Bluetooth `HELLO`/`INFO`). *Draft 2* will be draft 1
  plus [PROPOSALS.md](PROPOSALS.md), after Sukrara has made those changes. A NIP pull request makes sense only for
  draft 2 (see [drafts/nip-pr.md](drafts/nip-pr.md)).
- The name is vendor-neutral on purpose. "Open Glucose Stream" and "OGS" are working titles. Constants that still
  carry the reference app's name (`sukrara/invite/v3`, the `sukrara.mauri.app/join#` prefix) are listed in one table
  (ogs-nostr N§3). P2 replaces them.
- **Not a medical device.** OGS moves numbers. Nothing in it is validated for treatment decisions, and a follower's
  copy can be late.

## Run the reference

```sh
cd spec/reference
uv run ogs vectors verify                 # signatures, NIP-44, derivations, invite checks, BLE frames + INFO, 27-step scenario
uv run python -m unittest discover tests  # crypto against the official NIP-44 and BIP-340 vectors, framing, CGMS
uv run ogs follow '<invite link>'         # join a real stream as a third-party follower (throwaway key)
# interop with the Sukrara side (repo v2 code + rust-nostr, in the spikes/nostr environment):
uv run --project ../../spikes/nostr python interop/sukrara_side.py oracle ../test-vectors/ogs-draft1.json
uv run --project ../../spikes/nostr python interop/sukrara_side.py fixture /tmp/fx.json && uv run ogs decode /tmp/fx.json
```

## Licence

The reference code is GPL-3.0-or-later, like the rest of this repository. The spec texts and the test vectors are
meant to be reusable by anyone, including closed apps, so they should go out under a permissive licence (CC0 or
CC-BY-4.0) when published. That's the maintainer's decision; see [docs/24](../docs/24-open-standard.md#publishing).
