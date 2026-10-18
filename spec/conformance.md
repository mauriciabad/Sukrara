# OGS conformance checklist (draft 1)

An implementation claims one or more **classes**. Each line is a MUST unless it says SHOULD. Machine checks:
`uv run ogs vectors verify` (the reference) and the vectors in `test-vectors/ogs-draft1.json` (for any language).
Section references: N = [ogs-nostr.md](ogs-nostr.md), D = [ogs-data.md](ogs-data.md), B = [ogs-ble.md](ogs-ble.md).

## Classes

| Class | For | Requires |
|---|---|---|
| **F — Follower** | a viewer app, watch companion, widget, home automation | — |
| **W — Writer** | a follower that adds treatments | F |
| **O — Owner** | the app that reads the sensor and publishes | — |
| **BF / BO — Bluetooth follower / owner** | the nearby link | F / O |
| **P — Portable owner** | can take over another app's stream | O |
| **C — CGMS bridge** (proposal P14) | local CGMS collectors | F or O |

## F — Follower

Crypto and events
- [ ] NIP-01 id and BIP-340 signature checked on every event before anything else (N§9.1.1)
- [ ] NIP-44 v2 decrypt with `(G_n.sec, author.pub)`; every NIP-44 vector of the official file passes
- [ ] NIP-59 unwrap: the seal's signer is the sender, and the rumor's `pubkey` equals it (N§6)
- [ ] `H`, `gk` and the `d`/`c` derivations reproduce `derivations` in the vectors (N§4)

Joining
- [ ] Parses the invite payload, derives `invite_key`, and reproduces `derivations.invite` (N§7.1–7.2)
- [ ] Accepts an invite wrap only if the seal's signer starts with the fingerprint and `x > now`; passes the 3
      `invite_checks` (N§7.3)
- [ ] Sends the access request with `token` and a profile to `I` on the invite's relays; shows the match code (N§7.4–7.5)
- [ ] Answers NIP-42 AUTH as `F` (inbox) and as the invite key (lookup), never as a personal key (N§8.2, N§12.1)

Acceptance (run the 27-step `scenario` and reach every verdict and the final state)
- [ ] Key delivery: only rumor 30501 sealed by `I`; keeps every epoch; refuses a `sid` change (N§9.1.3)
- [ ] Unknown `gk` → rekey request, rate-limited, and only for `I`/device authors (N§9.1.5)
- [ ] Roster only from `I`; `epoch` matches the `gk`; ordered by `(epoch, created_at)` (N§9.1.6)
- [ ] Chunks, live readings and alerts only from `roster.devices`; treatments from `devices ∪ writers` (N§9.1.7–9)
- [ ] Chunk and treatment `d` recomputed from the payload (N§9.1.7–8)
- [ ] Writer scope: a writer's version only for a new `id` or one it created; the wearer's own entry beats a writer's earlier copy (N§9.1.8)
- [ ] Expired events dropped (N§9.1.2); stale alerts (> 10 min) dropped; alerts deduplicated by `(id, state, n)` (D§7)
- [ ] After `revoked`, no new data is accepted (N§9.1.4)

Data
- [ ] Glucose is an integer in mg/dL, range 1–1000; times in seconds; treatment `u` in milliseconds (D§1)
- [ ] Unknown keys ignored; an unknown `v` dropped; unknown message `type`s ignored (D§10, N§11)
- [ ] Sensor taken from the newest live event by `created_at`; no `se` clears it (D§5)
- [ ] Treatments merged by `(u, o)` (D§6.2)
- [ ] SHOULD render avatar v1 or the fallback; MUST keep and forward an unknown avatar unchanged (D§8)

Privacy
- [ ] Never publishes kind 0 or any public event with `F`; uses a new `F` per stream (N§12.1)

## W — Writer
- [ ] Shows *add entry* only while its `F` is in the newest roster's `writers` (N§5.5)
- [ ] Publishes kind 30078 signed by `F` with tags `d`, `c`, `gk` and `expiration` (+30 d); `created_at` strictly increasing per `d`
- [ ] Treats an entry as accepted only when an echo signed by a roster device arrives with `u ≥` the sent `u` (N§5.5)
- [ ] Sends `o:"follower"`; changes or deletes only the entries it created (N§9.2)

## O — Owner
- [ ] Separate `I`, `D` and `G_n`, and a random `sid`; never a kind 0 on `I` or `D` (N§2, N§12.1)
- [ ] Every stream event carries `gk` (except the backup) and an absolute `expiration` (N§5)
- [ ] Live within 10 minutes of the reading; chunks replaced as the hour fills, with strictly increasing `created_at` (N§5.1–5.3)
- [ ] Roster republished on every change and rotation and at least every 7 days (N§5.4)
- [ ] Invite as a NIP-59 wrap sealed by `I` to `invite_key(s)`, expiring with the invite (N§7.3)
- [ ] Single claim (first received), `invite-used` to later requests, an explicit human Allow or Deny (N§7.4)
- [ ] Revoke = rotate: key deliveries to the remaining followers, `revoked` to the removed one, new roster, republish history (N§10)
- [ ] Accepts writer entries only per N§9.2 (a writer changes only its own entries); republishes them signed by `D` (the echo)
- [ ] Answers rekey and history requests only for approved, non-revoked followers, rate-limited (N§9.2)
- [ ] Publishes to several relays, at least 2 s apart per relay (N§8.4)
- [ ] Sends the sensor update on change and during warm-up; never a serial number (D§5)

## BF / BO — Bluetooth
- [ ] Service and characteristic UUIDs and properties as in B§2; scan by service UUID; BO: no name in the advertisement or scan response (B§3)
- [ ] Framing reproduces the `ble` vectors at MTU 23, 185 and 517; at most 512-byte frames (B§4.1)
- [ ] One notification in flight per central; one write in flight (B§4.2)
- [ ] Every event checked as in N§9, whatever arrived over Bluetooth (B§8)
- [ ] BO: backlog on `HELLO`; never the owner backup or the invite; pushes every publish (B§6)
- [ ] BO: nearby pairing makes the wearer **type** the match code (B§7)
- [ ] BO: `INFO` carries `nonce` and `m` (reproduces the `ble.info_m` vector); `i`, `relays` and `pair` only while pairing (B§5)
- [ ] BO: no expired event in a backlog (B§6)
- [ ] BF: a fresh `nonce` per `HELLO`; writes nothing but `HELLO` until `INFO` proves the stream (`m`, or `i` while pairing); leaves another wearer's phone (B§6)
- [ ] Reassembly: 30 s idle timeout, 256 KiB cap, at most 16 buffers (B§4.3)
- [ ] BO SHOULD: check writes before answering; cap centrals; per-central rate limits; one backlog per few seconds (B§8)

## P — Portable owner
- [ ] Reads and writes the Appendix A backup (`sid`, `epochs`, `followers`, `writers`); rotates on takeover (N Appendix A)

## C — CGMS bridge (proposal)
- [ ] Off by default; bonding with LE Secure Connections; read-only control points; never offered for dosing (B Annex B.2)
- [ ] Reproduces the `cgms` vectors (B Annex B.3)

## Where the reference apps stand (2026-10-19, after P3–P6, P10 (part), P12, P13 and P15)

| | Sukrara Android | Sukrara Mac | `spec/reference` |
|---|---|---|---|
| F | ✅ (expiration and roster epoch not checked: P10) | ✅ (expiration, roster epoch and equal-`created_at` rosters: P10) | ✅ all vectors and the scenario |
| W | ✅ | ✅ | builders only |
| O | ✅ | — | builders only (vectors) |
| BF | ✅ | ✅ | framing, `INFO` and the owner check |
| BO | ✅ | — | framing, `INFO` and the backlog filter |
| P | ✅ | — | — |
| C | — | — | encoder and vectors |
