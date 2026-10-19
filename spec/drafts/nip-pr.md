# Draft: pull request to nostr-protocol/nips

**Not to be sent.** On 2026-10-19 the user decided against a pull request to nostr-protocol/nips; draft 3
([`../draft3`](../draft3)) goes to a standalone spec repository instead (docs/24b → Publishing). Kept for the record:

**Not sent.** Publishing is the maintainer's call. Send it only after draft 2 (PROPOSALS P1, P2 and P7 in Sukrara),
so the NIP has no app name in its constants. The file to submit is `ogs-nostr.md`, cut down to NIP size:
- keep N§1–N§9 and N§12–N§14;
- inline the payload tables from `ogs-data.md` D§3–D§7;
- move lifecycle detail and the owner backup to "Implementation notes".

---

**Title:** NIP-XX: Private glucose streams

**Body:**

This NIP lets a person wearing a continuous glucose monitor share a live, end-to-end encrypted glucose stream with
people they approve, through ordinary relays. There's no account, no server and no public profile. It's the Nostr
equivalent of LibreLinkUp, Dexcom Follow or a Nightscout site, without the vendor cloud or the self-hosted server
that sees everything.

It defines:
- **kinds:**
  - `21078` live reading (ephemeral) and `21079` alert (ephemeral);
  - `31078` hour chunk, `31079` roster and `31080` treatment (addressable);
  - rumor kinds `30500`, `30501` and `30502`, used inside NIP-59 gift wraps only;
- **one encryption per event** (NIP-44 v2, to a shared read key), opaque `d`/`c` tags derived from a secret stream
  id, and a `gk` tag naming the read-key epoch;
- **an invite and consent flow:** a 63-character link holds the identity's fingerprint and a secret. The invite is a
  NIP-59 gift wrap to a key derived from that secret. Joining is an access request, a human Allow, then a key delivery
  by gift wrap. Removing a follower rotates the read key;
- **acceptance rules**, which make the stream safe to carry over any relay or over Bluetooth, and a privacy section.
  The main rule: stream keys never publish kind 0 or any other public event.

It reuses NIP-01, 40, 42, 44 and 59 and adds no cryptography.

Why new kinds rather than NIP-78: NIP-78 asks interoperable data to use dedicated kinds, and lets relays serve kind
30078 only to its author. Under that rule, followers would never get the data.

Implementations:
- Sukrara (Android and macOS, GPL-3.0): an owner and a follower in production use by its author since 2026-10;
- an independent Python reference (standard-library crypto) with deterministic test vectors: 30 events, a 27-step
  acceptance scenario, and Bluetooth frames. Repository: <link to the spec repo>.

A companion spec, not proposed as a NIP, carries the same signed events over Bluetooth LE for followers with no
internet.

Open questions for reviewers:
1. Kind numbers: are 21078/21079 and 31078–31080 acceptable?
2. Should the generic part (stream keys, roster, invite, rotation) become its own "private group stream" NIP that
   other sensors could use, with glucose as one payload profile? It's close to the open NIP-DA proposal
   (nostr-protocol/nips#2411, scoped data grants).
3. Is a NIP-59 gift wrap to a link-derived key acceptable as the invite carrier?
