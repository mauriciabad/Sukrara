# 24 — An open standard for CGM sharing over Nostr and Bluetooth

*2026-10-18 · investigation report · builds on [03](03-nostr.md), [06](06-sharing-and-keys.md),
[07](07-bluetooth-followers.md), [14](14-nostr-identity.md), [15](15-alerts.md) · spec: [`spec/`](../spec) ·
reference: [`spec/reference`](../spec/reference) · evidence: [`evidence/24-interop.log`](evidence/24-interop.log)*

## Goal

The user wants Sukrara's sharing protocols *"standardized so other apps could attach to it — making a proper standard
would be cool"*. The biggest privacy worry stays the same: a public Nostr identity linked to a glucose stream.

## Result

| | Result |
|---|---|
| Spec set | **Open Glucose Stream (OGS) draft 1**, in `spec/`, vendor-neutral, written in RFC 2119 language: a data model, a NIP-style Nostr spec, a Bluetooth GATT spec, a conformance checklist, a list of 15 proposed changes, and test vectors |
| Draft 1 = today's wire | Sukrara 0.11.17 already speaks it, so third parties can interoperate **now**. The cleanups are proposals for draft 2 ([PROPOSALS](../spec/PROPOSALS.md)). |
| Independent implementation | `spec/reference`: Python with **standard-library crypto** (BIP-340, NIP-44 v2, NIP-59, HKDF, ChaCha20, bech32), a follower that applies every acceptance rule, vector generation, and a live relay follower |
| Crypto correctness | all 101 valid and 20 invalid official NIP-44 vectors, all 19 BIP-340 vectors, RFC 5869 A.1; 14 unit tests |
| Test vectors | `spec/test-vectors/ogs-draft1.json`: throwaway keys from public strings, 28 signed events (nonces, timestamps and aux pinned, so they're reproducible byte for byte), plaintexts, 3 invite checks, a **25-step acceptance scenario**, Bluetooth frames at 3 MTUs, CGMS packets |
| Interop, Sukrara side → reference | events from the repo's v2 code and rust-nostr 0.45.1 (the NIP-44/59 library both Sukrara apps use), plus the 0.11 additions (alerts, treatments, sensor life, v3 invite with a profile): **all accepted** by the reference follower |
| Interop, reference → Sukrara side | **63/63 checks**: rust-nostr verifies, decrypts and unwraps every reference vector; the repo's `cgm2`/`ident` code derives the same tags, invite key, link and match code |
| Interop with the apps' own vectors | the reference reproduces `spikes/nostr/identity/vectors.json`, which Android `InviteTest.kt` and Mac `-qrselftest` pin (invite key, short link, derived account) |
| Live, public relays | repo v2 owner ↔ reference follower on nos.lol, relay.primal.net and nostr.mom: invite found, request, match code 1141, Allow, key, roster, 2 hour chunks, treatment, alert, then live every 30 s. **All accepted, 0 rejected.** |
| CGMS (0x181F) | **Not the sharing protocol** (no consent, no end-to-end encryption, no treatments, one collector per bond). It's worth having as an **optional read-only bridge** so xDrip+'s GluPro collector can show a stream (P14). The encoder and vectors are written; no app has it yet. |
| Publishing | Nothing sent. Drafts for the NIP PR, a site page and 5 outreach messages are in [`spec/drafts/`](../spec/drafts). The path is [below](#publishing). |

## Audit: specs vs. the apps

Two read-only audits compared `PROTOCOL-v2.md`, `PROTOCOL-identity.md`, `alerts/SPEC.md` and docs/07 with Sukrara for
Android (`core/Proto.kt`, `Ble*.kt`, `owner/`, `follower/`, `alerts/`, `treatments/`) and Sukrara for Mac
(`NostrV2.swift`, `BLEV2Central.swift`, `Treatments.swift`, `Alerts.swift`). The wire format is solid and the two
apps agree on almost everything. What a third party could **not** have implemented from the repo's specs alone:

| Kind of gap | Examples (details and file:line refs in [PROPOSALS](../spec/PROPOSALS.md)) |
|---|---|
| Undocumented | Gift-wrap TTLs. Tag order. `gk` hashes raw key bytes, not hex. The `clear` alert's fields. The owner backup's `writers`, and its invites' `label`/`nearby`/`claimedBy`/`done`. The `sukrara:join#` scheme both apps accept. Backlog order and limits (40 entries, 200 treatments, 10 wraps). Reassembly behaviour. The phone's own Bluetooth name in the scan response. |
| Wrong in the spec | The match code is `sha256 mod 10000`; PROTOCOL-v2 says "first 4 digits". docs/07's `INFO` lacks `pair.profile`. The alert sits before the treatments in the backlog, not "next to live", and chunks go newest first. |
| Implementation-defined | Integer vs float tolerance (`org.json` writes `180.0` as `180`; Swift `Decodable` rejects a float `t`). Equal-`created_at` rosters (the Mac accepts, Android ignores). Readings: first copy wins. Rate rounding (live 2 decimals, alerts raw). |
| Divergent between apps | The Mac sends wraps **without expiration**, lets a key delivery **change `sid`**, never checks a chunk's `d`/`c`, sends a rekey for any author, and sends `o:"mac"` as a writer. The Android Bluetooth follower reconnects to the **first owner found** without checking `INFO.i`. |
| Branded | `sukrara/invite/v3` (HKDF salt), the `https://sukrara.mauri.app/join#` and `sukrara:join#` prefixes, the `sukrara/avatar/v1/dots:` seed, the `cgm2:` namespace (harmless). |
| Missing for a standard | Versioning rules (unknown keys and enum values, how `v` evolves), capability discovery, expiration checks on the receiver, Bluetooth limits, a privacy and a security section, conformance classes, test vectors |
| Security | **A writer can tombstone or change any entry, including the wearer's own** (P4). NIP-78 lets relays serve kind 30078 only to its author, which would starve followers (P1). Bluetooth has no rate or size limits (P13). |

Versioning, units, timestamps and trend were consistent: mg/dL integers, seconds (milliseconds only for the treatment
`u`), and the rate in mg/dL/min. Sensor metadata carries no serial, deliberately. Invites, consent, revocation by
rotation and key rotation on a phone change were complete, but spread over three documents.

## Prior art

| Source | What it gave | Used how |
|---|---|---|
| NIP-01, NIP-40, NIP-42, NIP-44 v2, NIP-59 | events, expiration, AUTH, encryption, gift wraps | **reused as is**, no new cryptography |
| NIP-17 (private DMs) | gift-wrapped rumors to a recipient | the same pattern for requests and key deliveries (rumor kinds 30500–30502) |
| NIP-78 (app data, kind 30078) | what draft 1 uses for chunks, roster and treatments | **a problem:** NIP-78 says interoperable data needs dedicated kinds, and relays SHOULD serve 30078 only to its author. → P1: 31078/31079/31080, which are free in `registry-of-kinds` (cloned 2026-10-17). The owner backup stays on 30078: that is NIP-78's use case. |
| NIP-90 (DVMs) | — | `unrecommended` ("this got totally out of control"), and unrelated |
| NIP-EE, Marmot (MLS groups) | group key agreement | not used: heavy (KeyPackages, commits), relay-dependent, no Bluetooth story. OGS's single writer and many readers is simpler with one read key and rotation. |
| NIP-BE (Nostr over BLE) | prior art for the BLE transport | **not adopted** (`unrecommended`; advertises `0x180F`, the SIG Battery Service UUID; placeholder characteristic UUIDs; DEFLATE on ciphertext; 256-chunk cap). The comparison is in ogs-ble Annex A. |
| Open NIP PRs nips#2411 (NIP-DA, scoped data grants), #1893 (NIP-PNS, keys derived from an nsec) | the grant/rotation and derivation ideas | cited. The NIP PR draft asks whether the generic stream layer should be its own NIP. |
| Health, IoT or sensor NIPs | none in the nips clone or the kind registry | — |
| Nightscout (`cgm-remote-monitor` API v3 swagger, careportal) | the de-facto data model: `sgv` and `direction`, `units` "strongly recommended", treatment `eventType`s | the **directions table** (D§2), the treatment mapping (D§6.3), and the follower-side Nightscout API (D§9). No long-acting eventType exists (xDrip+ uses `insulinInjections`). |
| Tidepool uploader (`objectBuilder.js`) | `cbg` stored in mmol/L with a required `units` | the opposite choice; OGS fixes **mg/dL integers** on the wire, as Nightscout, LibreView and CGMS do |
| Android broadcasts (xDrip+ `BgEstimate`, GlucoDataHandler's receivers) | local interop today | an optional follower bridge, with user consent (D§9) |
| Bluetooth SIG CGMS 0x181F / CGM Profile, Glucose Service 0x1808 | a standard sensor-to-collector link; xDrip+'s GluPro collector (`libglupro`, Nordic library) speaks it | **verdict below**; the field layout follows the Nordic parser GluPro uses |

### CGMS verdict

A phone *can* expose CGMS (Android lets a GATT server register SIG service UUIDs), and xDrip+'s GluPro collector would
attach: it bonds, notifies on 0x2AA7, indicates on RACP and SOCP, reads CGM Status and backfills with "≥ time
offset". But CGMS is a sensor-to-one-collector link:
- consent is just the bond, with no per-follower revocation except unbonding;
- there's no end-to-end encryption and no internet path;
- there are no treatments (the Glucose Service's context has carbs and medication, but only tied to fingerstick
  records);
- alerts are status bits without episode, repeat or clear;
- warm-up has no bit;
- history counts in uint16 minutes since the session started.

So OGS keeps its own service, and CGMS becomes an **optional, bonded, read-only bridge**, off by default and never for
insulin dosing. ogs-ble Annex B has the mapping, and `spec/reference/ogs/cgms.py` the encoder and vectors. The next
step would be a PoC against xDrip+ GluPro on an emulator. The octet order of the annunciation field follows Nordic's
parser and still has to be checked against the CGMS 1.0.2 text.

## Decisions

1. **Name: Open Glucose Stream (OGS)**, vendor-neutral. Sukrara is the reference implementation. All draft 1
   constants that carry the app's name sit in one table (ogs-nostr N§3).
2. **Draft 1 describes today's wire exactly**, so other apps can attach to Sukrara now. Breaking cleanups wait for
   draft 2, applied in Sukrara first, without backwards compatibility (the repo rule). A NIP PR only makes sense for
   draft 2.
3. **Three documents along the layers:** the data model (transport-free), Nostr, and Bluetooth. The same signed bytes
   travel on both transports, so Bluetooth adds framing, not trust.
4. **The privacy rule is normative:** stream keys (`I`, `D`, `F`, `W`, `G`) MUST NOT publish kind 0 or any other
   public event. A main key may only *derive* `I` one-way. Profiles exist only inside encrypted payloads (N§12.1).
5. **Dedicated kinds in draft 2** (P1). Blending into NIP-78 traffic was never real privacy, because kind 21078 and
   the `gk` tag already mark a stream. NIP-78 relays could refuse to serve followers.
6. **mg/dL integers and a rate in mg/dL/min** on the wire. Arrows are derived (Nightscout directions), never sent.
7. **Explicit versioning rules** (D§10, N§11): ignore unknown keys and message types, drop an unknown `v`, and in
   draft 2 add `caps` and open enumerations (P8, P9).
8. **Conformance classes:** follower, writer, owner, Bluetooth follower and owner, portable owner (the owner backup as
   an optional takeover format, so a user can switch owner apps), and CGMS bridge.
9. **Test vectors are deterministic** and come with a *scenario* (events → expected verdicts → final state), the way
   the repo already pins alert rules with timelines.
10. **The reference is independent:** standard-library crypto, no rust-nostr, no Sukrara import. The interop harness
    lives apart (`spec/reference/interop/`) and runs in the `spikes/nostr` environment.

| Reused | New in OGS |
|---|---|
| NIP-01/40/42/44/59, the NIP-19 `nsec` for the delivered key, HKDF (RFC 5869), Nightscout directions and treatment types, mg/dL, the SIG CGMS layout for the bridge | the stream key model (`I`, `D`, `G_n`, `sid`), opaque tags and `gk`, the payloads, the invite link with fingerprint and the invite wrap, single claim and match code, acceptance rules, hour chunks, alerts, the GATT service and framing |

## Proposed changes to Sukrara (for an integration step)

All 15 are in [`spec/PROPOSALS.md`](../spec/PROPOSALS.md) with evidence. In priority order:
1. **P4 writer scope** (security): writers may only edit their own entries.
2. **P1 dedicated kinds** and **P2 neutral invite and namespace**: the two breaking changes a NIP needs. Do them together
   with P7 (one version number).
3. **P5, P6, P10** (the Mac's missing wrap expiration, `sid` change and chunk checks; receiver expiration and roster
   epoch).
4. **P12, P13** (Bluetooth privacy and limits): drop the phone name from the scan response, blind `INFO.i`, check the
   owner before writing, add limits.
5. **P3** (doc fixes, no code), **P8, P9, P11** (caps, open enumerations, reading corrections).
6. **P14** CGMS bridge PoC; **P15** retire the outdated Python v2 CLI (it can't pair with the Mac any more).

Each lands on Android and Mac in the same round (parity groups `protocol-v2`, `identity`, `treatments`), with the
reference vectors regenerated for draft 2. The reference scenario then becomes a cross-platform guard, like the alert
timelines.

## Publishing

Nothing has been published, and no PR, issue or post has been made. This is the proposed order:

1. **Integrate draft 2 in Sukrara** (the proposals above) and regenerate the vectors. Until then, a NIP would carry
   `sukrara/...` constants.
2. **A versioned spec repository**, for example `github.com/<owner>/open-glucose-stream`:
   - `spec/` and `test-vectors/` (semver tags: `draft-2`, `1.0.0`), the reference as a package;
   - a **permissive licence** for the text and vectors (CC0 or CC-BY-4.0), so closed apps can implement it too. The
     reference can stay GPL or move to MIT. That's the user's call.
3. **A page on sukrara.mauri.app**: [`drafts/site-page.md`](../spec/drafts/site-page.md) → `docs/ogs.md`, built by
   the existing site workflow.
4. **A NIP PR to nostr-protocol/nips**: [`drafts/nip-pr.md`](../spec/drafts/nip-pr.md) (the Nostr part only; the
   Bluetooth companion stays in the spec repo). Open questions for reviewers: kind numbers, a generic "private stream"
   NIP, the gift-wrapped invite.
5. **Outreach**, after 2–4, on each project's public channel: [`drafts/outreach.md`](../spec/drafts/outreach.md) has
   texts for xDrip+ (follower source, and the GluPro bridge), Juggluco, GlucoDataHandler, Nightscout (mapping review)
   and LoopFollow/nightguard.
6. Post from a project account, never from stream keys.

## In Sukrara

**Nothing was added to the app in this investigation** (it is a standalone PoC). The proposals above are the candidate
integration list. Every one of them is still open: none was rejected, and none was taken in.

## Not done / open

- **The interop proof stops short of the apps' Kotlin and Swift code paths.** The Sukrara side in the proof is the
  repo's Python v2 code with rust-nostr (the library the apps use) and payloads shaped from the audit. The apps' own
  code is tied in only through `identity/vectors.json`. Next: run `ogs follow` against a Sukrara Android
  **emulator** owner (synthetic source), and feed `ogs-draft1.json` to the Mac `FollowerV2.onEvent`. The emulator
  wasn't touched, because another session was using `emulator-5554`.
- The CGMS octet order needs checking against the SIG text and a real GluPro run.
- Bluetooth was not exercised (no Python Bluetooth owner exists, and docs/07 covers the radio). The reference checks
  framing only.
- Licence choice and the spec repository's name.

## Files

| Path | What |
|---|---|
| `spec/README.md`, `ogs-data.md`, `ogs-nostr.md`, `ogs-ble.md`, `conformance.md`, `PROPOSALS.md` | the spec set |
| `spec/test-vectors/ogs-draft1.json` | vectors (throwaway keys; listed in `tools/check/allow.json` `secret_ok` because key deliveries contain an `nsec` by design) |
| `spec/reference/ogs/` | `crypto.py`, `nostr.py`, `model.py`, `events.py`, `follower.py`, `ble.py`, `cgms.py`, `vectors.py`, `relay.py`, `cli.py` |
| `spec/reference/tests/test_ogs.py` | 14 offline tests |
| `spec/reference/interop/sukrara_side.py` | the Sukrara side: `oracle`, `fixture`, `serve` (runs in the `spikes/nostr` environment) |
| `spec/drafts/` | NIP PR, site page, outreach (unsent) |
| `docs/evidence/24-interop.log` | the oracle, fixture decode, live relay run and self-checks |
