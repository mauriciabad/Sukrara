# OGS draft 3: requirements and design rationale

`draft 3` · 2026-10-19 · [index](README.md)

Draft 1 wrote down what one app (Sukrara 0.11) sends. Draft 3 starts again from what people sharing glucose need,
re-checked against the current state of the art (Nostr NIPs and open proposals, health data standards, the
consent models of commercial follow apps, group-key research and Bluetooth platform limits). Breaking changes were
allowed. One constraint stands above everything: **no public identity may be linked to a glucose stream.**

## 1. Requirements

| # | Requirement | Notes |
|---|---|---|
| R1 | **Actors.** A wearer, possibly a child whose parent administers the stream. The wearer's devices (phone, watch, desktop). Members: family viewers; carers who log treatments and acknowledge alerts; school staff or babysitters for a period; clinicians who need history but not a live feed; emergency responders with no prior relationship; watch and widget apps; local integrations (Nightscout, Home Assistant). | Dexcom Follow allows 10 followers, LibreLinkUp 20. Design for about 1–20 members. |
| R2 | **Privacy.** No public identity linked to a stream. No serial, account id, device model or location in the data. Unlinkable over time to onlookers. Members don't learn who else reads. | the user's standing rule |
| R3 | **Consent and control.** Explicit approval per member, with an informed offer (what, since when, until when). The wearer can revoke, pause, limit history, limit duration. A member can leave. | No commercial follow app limits history or duration today. |
| R4 | **End to end.** Confidential and authentic whatever carries it. Relays, Bluetooth and files are untrusted. | |
| R5 | **Offline and nearby.** Works without internet over Bluetooth, with the same data and rules. | a requirement since investigation 07 |
| R6 | **History.** A new member backfills the agreed window. Gaps are filled. Clinicians see 90 days. Late and corrected readings are handled. | |
| R7 | **Multi-device.** Several publishing devices per wearer; several devices per member, each revocable alone. | |
| R8 | **Revocation.** A removed member reads nothing new and can't even recognise new traffic. | draft 1 kept a constant `sid`: removed followers could still recognise the stream |
| R9 | **Emergency access.** Someone without an invite can see the glucose when the wearer can't approve. | |
| R10 | **Data model.** Readings with HI/LO and quality; calibrations and fingersticks; sensor session lifecycle; device status; insulin, carbs, exercise, notes; alerts and acknowledgements; settings (unit, target); local time. Pump and automated delivery are out of scope for now. | AID needs basal, IOB and COB: a later extension |
| R11 | **Units and time.** One canonical unit, one conversion constant, local time recoverable. | |
| R12 | **Extensibility.** New types and values must not break old apps. Major versions must not be confused with each other. | |
| R13 | **Bridges.** Map cleanly to Nightscout, HL7 FHIR CGM IG, Health Connect, HealthKit and Tidepool. | |
| R14 | **Implementable** in Kotlin, Swift and Python (standard library) on phones and watches. Deterministic test vectors. About one event a minute. | |
| R15 | **No server, no account.** Public relays only, which keep little and expire it. | |

The threat model is in [ogs3-security.md](ogs3-security.md) S§1: onlookers, relay operators, removed members,
misbehaving members, invite interceptors, nearby observers and device thieves.

## 2. Decisions

Each decision gives what was considered, what was chosen and why, and how it differs from draft 1.

### D1. The unit of authority is a signed record

- **Considered:** draft 1's model, where the visible author of a Nostr event *is* the authority, and the owner
  re-signs (echoes) a writer's entry; a model where only the owner publishes, and writers submit through the inbox.
- **Chosen:** every record is a NIP-01 event signed by its author's own key, inside the encryption. It stays signed
  by its author on every copy, relay, Bluetooth or export.
- **Why:**
  - Authority survives any carrier.
  - Contributors can publish directly, with no echo.
  - Attribution is verifiable.
  - It removes draft 1's hardest rule: the writer-scope logic around echoes and first-seen creators.
- **vs draft 1:** draft 1 had authors in the clear and an echo.

### D2. Group keying: a day-key tree, not a single epoch key, a ratchet or MLS

- **Considered:**
  - **Static epoch key** (draft 1): simple, but all-or-nothing within an epoch. A new follower reads the whole epoch,
    and a time limit needs a rotation at the end, with the owner online.
  - **Hash ratchet** (Signal sender keys, Megolm, the open NIP-171): it gives forward secrecy and a history start,
    but no end. A ratchet goes forward forever.
  - **MLS / Marmot** (RFC 9420, NIP-EE superseded by Marmot):
    - for: PCS per member update, per-message FS;
    - against: commits must arrive in order (Marmot spends pages on retained epochs and trial decryption because
      relays don't guarantee it); it needs an MLS stack (OpenMLS or mls-rs with uniffi, no vetted Python); its
      KeyPackages and Welcomes are tied to Nostr accounts, which would need throwaway accounts under our privacy
      rule;
    - and its O(log n) rekey buys nothing at n ≤ 20.
  - **One ciphertext per recipient** (the open NIP-TPLD): it puts a `p` tag per follower on every event, publishing
    the follower graph.
  - **NIP-DA scoped data grants** (open PR #2411): a symmetric scope key and rotation. This is the right primitive
    for the content key, but it is still all-or-nothing per generation.
- **Chosen:** an epoch root and a GGM binary tree of depth 10 over days (C§4).
  - A grant is the minimal cover of exactly the agreed days.
  - Removal rotates the epoch.
  - Members puncture their covers for forward secrecy.
  - The administrator rotates every 30 days for PCS.
- **Why:**
  - It is the only option where the history start **and** the end of access are enforced by the keys, with no action
    at the end.
  - It is offline-tolerant: a member never needs anything but its grant.
  - It is about 30 lines of HMAC.
  - FS and PCS come close to MLS's in practice for one writer (S§5).
- **vs draft 1:** draft 1 had one `G_n` per epoch, delivered as an `nsec`, used through ECDH.

### D3. Metadata: blinded per-day authors

- **Considered:**
  - constant visible authors (draft 1: `D`, `I` and writers were linkable forever);
  - a fresh one-time key per event (Marmot), which breaks addressable replacement and lets anyone who sees a tag
    flood it;
  - a shared per-day key all members can sign with, which lets any member overwrite others' objects.
- **Chosen:** each publisher's key, tweaked by a secret per day and lane (C§5.3), as Tor v3 onion services blind
  their keys per time period.
  - Only the publisher can sign. Every member can compute the public key and subscribe by `authors`.
  - Relays see unrelated keys each day.
- **Why:**
  - It keeps addressable replacement and author-based filtering (spam resistance).
  - Members can't overwrite each other.
  - No field links days, and removed members can't recognise new traffic.
- **Limit (review 24c):** days are unlinkable by content, not by behaviour. A relay sees a member's subscription span
  several days, and a once-a-minute rhythm continues across midnight under the next key. The construction still
  earns its keep for the first two reasons and for removed members; S§2 states the limit.
- **vs draft 1:** draft 1's opaque `d`/`c` tags came from a `sid` that never rotated, and its authors were in the
  clear.

### D4. One addressable kind, lanes, and no ephemeral events

- **Considered:**
  - draft 1's ephemeral live kind plus addressable chunks;
  - dedicated kinds per object type (proposal P1);
  - a regular kind with negentropy.
- **Chosen:** every stored object is kind 31078. Lanes (`g`, `r`, `m`) separate glucose from other records and from
  membership for filtering. The live channel is the current block republished.
- **Why:**
  - Kinds are visible, so one kind leaks less.
  - Relays push new versions of addressable events to subscribers anyway.
  - Many relays store "ephemeral" events regardless (measured for draft 1).
  - It is one mechanism instead of two. NIP-78 kinds are out: relays SHOULD serve them only to their author.
- **Cost:** about one replaced event a minute while a sensor runs, as in draft 1.

### D5. Days, not hours, as the access unit

- **Considered:** hour leaves, so that "school hours" would be enforced by keys.
- **Chosen:** UTC days.
- **Why:**
  - Hourly leaves multiply the authors a member must subscribe to (24 × publishers × lanes a day).
  - Backfilling 30 days of entries would need 720 authors per publisher.
  - "School hours" stays a policy (S§12).

### D6. Inboxes that rotate

- **Considered:**
  - draft 1, where every request went to `I`, a constant `p` tag;
  - deterministic wrapper keys from a shared secret (the open nips#2397).
- **Chosen:**
  - a fresh inbox per invite;
  - a fresh administrator inbox per epoch, announced in the membership;
  - members choose their own inbox key, separate from `M`.
- **Why:**
  - `I` and `M` never appear outside encryption.
  - Inbox keys change on every rotation.
  - It is simpler than #2397 and needs no new NIP.

### D7. Invites that say what they offer

- **Chosen:**
  - a version byte, the fingerprint and the secret;
  - optional relay hints, to remove the dependence on one app's default relays;
  - any web host or the bare `ogs:` text;
  - an **offer** inside (scopes, history days, duration);
  - a 6-digit match code (Bluetooth numeric comparison uses 6), typed for nearby joins.
- **vs draft 1:**
  - draft 1 had an app-branded URL and salt, and 4 digits;
  - consent was uninformed: approval gave everything.

### D8. Contributors publish directly; entries are keyed by creator

- **Chosen:**
  - an entry's identity is `(by, id)`;
  - only its creator or a wearer device may write a version.
- **Why:**
  - A writer can't even address the wearer's entries, so no first-seen rule is needed.
  - It replaces draft 1's echo and P4's creator tracking.

### D9. Membership lists contributors only

Readers don't author anything, so nobody needs to verify them. Leaving them out means members don't learn who else
follows (R2). Draft 1's roster listed writers, too. The difference is that draft 3 also gives each contributor's
window and scopes.

### D10. Data model

- **Units:**
  - **Considered:**
    - mmol/L storage, as in Tidepool;
    - a UCUM-coded `{value, unit}`, as in FHIR and Open mHealth.
  - **Chosen:** integer mg/dL. Every sensor reports mg/dL, the Bluetooth CGM Service is mg/dL only, and integers
    are exact and compact. FHIR and Open mHealth get `unit` added at the bridge.
  - **Conversion:** 18.01559, the value Nightscout, Tidepool and xDrip+ use. AndroidAPS, Juggluco and Health Connect
    use 18.0 and are documented as such.
- **HI/LO and quality** as row flags, after the CGMS annunciations and Tidepool's out-of-range annotation, instead of
  Nightscout's in-band 39/401.
- **Sessions** as records with a random id and a lifecycle (CGMS session start and run time, Nightscout Sensor
  Start/Stop), and still **no serial**.
- **Alerts** get Tidepool's three priorities, an open `kind`, and a fallback `text`. Acknowledgements between carers
  are new.
- **Entries** split into typed records (`insulin`, `carbs`, `bg`, `exercise`, `note`): each has its own fields, and
  the bridges (HealthKit insulin reason, Health Connect nutrition, FHIR) need them.
- **Local time:** a `tz` offset on records and a `zone` name in settings, as Nightscout, Tidepool and Health Connect
  carry them.
- **Open enumerations** everywhere, and no `v` field.

### D11. Bluetooth without a constant identifier

- **Considered:**
  - draft 1's constant service UUID (anyone in range learns "this person shares glucose": a health inference in
    public);
  - a constant UUID with rotating service data;
  - Encrypted Advertising Data.
- **Chosen:** advertised UUIDs derived from the day key, rotating every 15 minutes; GATT UUIDs per day; a constant
  pairing service only while the wearer pairs in person.
- **Why:**
  - Rotating service data still needs a constant UUID in the same advertisement.
  - EAD has no phone API.
  - The cost is a scan-filter update every 15 minutes, and iOS background limits, which are documented (B§3.3).

### D12. Emergency access as a standing invite

Auto-approved, multi-use, read-only, short window, always notifying the wearer (C§8.7). It needs no new mechanism,
only invite policy. It is OPTIONAL because it trades privacy for safety, and that choice is the wearer's.

### D13. Short relay retention

Expiration on everything: 2 days for live blocks, 30 for archives and entries. Members keep their own history.
Health data on public relays should be as short-lived as possible, and history requests cover the rest.

### D14. Publishing

The spec goes to a standalone repository under a permissive licence. Per the user's decision of 2026-10-19 there is
**no pull request to nostr-protocol/nips**. Kind registration in `registry-of-kinds` can follow once the kinds are
stable.

## 3. What the state-of-the-art review changed

| Finding (source) | Effect on draft 3 |
|---|---|
| NIP-78 and `registry-of-kinds` `schema.yaml`: relays SHOULD serve kind 30078 only to its authenticated author | no 30078 anywhere; one dedicated kind |
| NIP-DA, nips#2411: a random scope key used directly as the NIP-44 conversation key | symmetric day keys; no more `nsec` of a group key |
| Marmot transports spec: fresh signing key per event, one routing tag, trial decryption, retained epochs for out-of-order commits | blinded authors instead of fresh keys (keeps replacement and filtering); confirmed not to use MLS over lossy relays |
| NIP-171 epoch tickets (nips#2428), Signal sender keys, Megolm | rotation on removal is the accepted design; ratchets give a start but no end, hence the tree |
| NIP-TPLD (nips#2309): a `p` tag per recipient | what not to do; no recipient tags on data |
| NIP-101h (nips#1937): health kinds 1351–1399 with plaintext tags | kinds avoided; privacy by design is not a given in prior proposals |
| NIP-44 now allows plaintexts over 64 KiB with a 4-byte length | envelopes still capped at 65 535 bytes for older libraries |
| NIP-59 kind 21059 ephemeral wraps (merged) | considered and not used: control messages must survive offline recipients |
| NIP-70 protected events, NIP-77 negentropy, NIP-62 vanish | OPTIONAL hooks: protected envelopes, faster backfill, erasure |
| CGMS 1.0.2 annunciations, Tidepool `bg/out-of-range`, Nightscout 39/401 | HI/LO flags with the value as the bound |
| Tidepool `alert` (priority, acknowledged time), `backfilled`, `timezoneOffset`; Nightscout `utcOffset`; Health Connect `zoneOffset` | alert priorities, `ack`, `backfilled` flag, `tz` |
| Conversion constants differ: 18.01559 (Nightscout, Tidepool, xDrip+), 18.0156 (HealthKit), 18.0 (AndroidAPS, Juggluco, Health Connect) | one constant fixed, the others documented |
| HL7 CGM IG 1.0.0 (STU 1, 2025): LOINC 99504-3 / 105272-9, summary LOINC codes, `dataAbsentReason`, no treatments | the FHIR bridge; summaries are computed by members, not carried |
| Health Connect has no insulin record | the bridge table says so |
| Dexcom Follow (invite expiry, per-follower pause), LibreLinkUp, Nightscout roles (`careportal` = write) | scopes; pause = remove and re-grant. No product offers history limits, time-limited access or emergency access: draft 3 adds them. |
| iOS overflow area for background advertising; no phone API for Encrypted Advertising Data | derived 128-bit UUIDs, platform notes, relays as a fallback for iOS |

## 4. Open questions

- **Push for iOS followers:** a privacy-preserving wake-up service (N§9). Marmot's MIP-05 is the closest prior art.
- **Pump and AID data** (`basal`, IOB/COB, predictions): the first extension (D§12).
- **Owner backup** test vectors and a takeover test between two implementations (C§13).
- **Kind registration**, after a second independent implementation.
- **Cover traffic**: whether a fixed publishing schedule (S§3) should become a SHOULD.
- **Adding a publisher device** from another vendor (a watch that publishes) has no interoperable channel for the
  epoch roots yet (C§11.6). A grant whose cover is the root node, sent after an ordinary invite, would do it with
  nothing new; it needs a decision on who may hold roots.
- **CGMS bridge** octet order, still to check against a real collector (draft 1 P14).
