# 24c — Independent review of Open Glucose Stream draft 3

*2026-10-19 · review report · reviews [24b](24b-open-standard-draft3.md) and [`spec/draft3/`](../spec/draft3) ·
reference: [`spec/reference/ogs/d3`](../spec/reference/ogs/d3) · evidence:
[`evidence/24c-draft3-review-e2e.log`](evidence/24c-draft3-review-e2e.log)*

## Scope and method

The reviewer approached draft 3 as a skeptical expert in applied cryptography, Nostr, BLE and health-data
interoperability, and wasn't involved in writing it. It read every draft-3 document, the vectors, the Python
reference, 24b, and draft 1 for context. For each construction it formed its own view first and only then read the
author's rationale. Suspected flaws were checked against the reference with small attack scripts (throwaway keys,
synthetic data) before they were ranked. The live run used only public relays, throwaway keys and synthetic glucose.

Severity:
- **critical:** breaks a stated security or privacy property, or lets someone inject health data people act on;
- **major:** a real flaw or contradiction that an implementer would hit, or a claim the design doesn't meet;
- **minor:** underspecified, inefficient or fragile, with a limited impact;
- **editorial:** wording, notes, bounds.

Each finding has a decision: **Accepted** (changed in this branch), **Rejected** or **Deferred**, with the reason.

## Verdict

The core is sound and well chosen, and better than most prior Nostr health proposals:
- a GGM day tree for exact history windows;
- BIP-340 key tweaking for per-day authors;
- NIP-44/NIP-59 used as they are;
- signed records as the unit of authority;
- open enumerations;
- integer mg/dL.

The author's rejection of MLS for this shape (one writer, ≤ 20 readers, lossy relays) is right.

**As written, draft 3 was not safe to implement**, for two reasons:
- **The lifecycle was unfinished.** Removal rotated the keys for *reading*, but left the removed key's *write*
  authority alive in the old epoch, and every member still held that epoch's seeds up to three years ahead. A
  removed carer could keep adding insulin entries, and a lost phone could keep publishing glucose, and members
  accepted both. A proof of concept confirmed it.
- **The Bluetooth handshake authenticated only one side.** Any stranger who connected got the envelopes, which links
  a person in the street to a glucose stream: the project's hard rule.

Both are fixed here with small, conventional changes, and covered by vectors, unit tests and the live run. The
privacy claims are now stated honestly: days are unlinkable by content, not by traffic pattern.

After these changes the reviewer considers draft 3 a credible basis for a second, independent implementation, which
is the real test it still lacks. It isn't ready to freeze. Bluetooth has never run over the air, and the owner
backup has no vectors. (Device enrolment for other apps is now decided out of scope: D16.)

| Severity | Found | Accepted | Rejected | Deferred |
|---|---|---|---|---|
| critical | 2 | 2 | 0 | 0 |
| major | 4 | 4 | 0 | 0 |
| minor | 16 | 13 | 2 | 1 |
| editorial | 4 | 3 | 1 | 0 |
| **total** | **26** | **22** | **3** | **1** |

m14 counts as rejected: it was deferred in the review and then decided against (see [Decisions](#decisions)).

## What's good

- **Keys enforce the history window and the end of access** (C§4). Nobody else does this, and it costs about 30
  lines of HMAC. The cover algorithm is minimal and the reference tests it on random ranges.
- **The blinded-author construction is sound.**
  - The tweak is a PRF output that the attacker doesn't choose.
  - BIP-340 commits the public key in the challenge, so related-key forgeries don't transfer between `P` and
    `P + tG`.
  - It maps one-to-one onto libsecp256k1's `xonly_pubkey_tweak_add` / `keypair_xonly_tweak_add`.
  - It keeps addressable replacement and stops members from overwriting each other, which a shared day key would
    not.
- **Signed records as the authority** remove draft 1's echo and creator rules. The acceptance pipeline is ordered,
  named and tested by a scenario, so it can be implemented from the text.
- **No downgrade surface.** The version byte and the label prefix change together, and nothing is negotiated.
- **The invite flow.**
  - The offer is shown before joining.
  - The first join claims the invite.
  - A replayed join never re-admits (found by the live run).
  - The match code is typed for nearby joins.
- **The data model.**
  - Integer mg/dL with one documented constant.
  - HI/LO as flags with the bound as the value.
  - Sensor sessions without serials.
  - Open enums.
  - `tz` and `zone`.
  - Alert priorities and carer acks.
  - Bridge mappings that name each target's limits (Health Connect has no insulin record and no out-of-range
    value).
- **Expiration everywhere.** Relays are a pipe.
- **Reproducible vectors**, byte for byte, with pinned nonces.

## Findings

### Critical

**C1. A removed writer or a lost phone keeps write authority in the old epoch, and members accept it.**
- *Where:* C§9.2 ("grants add"), C§10.1 steps 5 and 12, C§11.2; `member.reindex`, `View.ended`.
- *What:* covers handed out before a rotation run to the epoch's end (up to 1 023 days). After removal:
  - every remaining member still holds the old epoch's future seeds, and still computes the old epoch's blinded
    authors for today and tomorrow;
  - the old epoch's newest membership still lists the removed contributor or device;
  - `epoch_end` set a flag that nothing read.

  So a removed carer (A3), or a thief with a lost phone (A7), can keep signing records in the old epoch. A PoC: after
  removal, a removed writer's 20 U insulin entry for tomorrow came back `accepted:insulin` at the reader *and* the
  administrator. A removed device could publish made-up glucose (and alerts) the same way, for years.
- *Why it matters:* false treatment and glucose data that carers act on. S§2 and S§4 claimed that removal solved
  this.
- *Suggestion:* end an epoch explicitly.
  1. When a listed key is removed, publish a last membership in the old epoch without it.
  2. `epoch_end` defines the epoch's last day.
  3. Members cut an ended epoch's cover at that day.
  4. Grants carry, for each older epoch, its `end` and its last membership, so a relay that withholds the
     relay-side records can't keep the hole open.
- **Accepted.**
  - Spec: C§9.2, C§10.2 (4), C§10.3, C§10.5, C§11.2, S§2, S§4, S§6.
  - Reference: `Epoch.clip`, `View.set_end`, `Owner.end_epoch`.
  - Vectors: `membership-A3`, `insulin-removed-writer` and `cgm-A-after-end` are both now `ignored:unknown-author`.
  - Tests: `Lifecycle`.
  - Live run: check 9, "removed writer F2 can't write into its old epoch", passes.
  - The cost, stated in C§10.5: a removed key's *legitimate* records that a member receives only after the removal
    are rejected, so replicas can differ on those.

**C2. Over Bluetooth, any stranger who connects receives the stream's envelopes.**
- *Where:* B§5, B§6, B§8 ("a central that finds the day service holds the day key"), B§9.
- *What:* only the publisher proved knowledge of the day key, and `HELLO` was answered with the backlog and live
  pushes. GATT discovery doesn't need the advertised UUID: anyone can connect to a device advertising a random
  UUID, list its services, and send `HELLO`. The envelopes are ciphertext, but they are kind 31078 with the day's
  blinded authors. The stranger then knows that *this person* shares a glucose stream, and can watch its
  once-a-minute rhythm on public relays.
- *Why it matters:* it breaks the hard rule (no public identity linkable to a stream; "even 'this person has
  diabetes' is health data", S§1), and contradicts B§9.
- *Suggestion:* mutual proof. `INFO` adds a `challenge`, and the member answers
  `AUTH {proof = HMAC(ble_key, "ogs3/ble/auth/" + challenge + "/" + nonce)}`. The publisher sends and accepts
  nothing else until it verifies.
- **Accepted.**
  - Spec: B§5, B§6, B§7 (the pairing service carries no stream envelope), B§8, B§9.
  - Reference: `ble.auth`.
  - Vectors: `ble.info.challenge`, `ble.auth`.
  - A test.

### Major

**M1. Unlinkability over time is overclaimed.**
- *Where:* S§2 ("A1, A2, A6 across days"), N§1 ("nothing … is shared with another day's envelopes"), rationale D3
  ("Days can't be linked"), README.
- *What:* nothing in the *content* links days, but behaviour does:
  - N§4.1 RECOMMENDS one subscription that lists yesterday, today, tomorrow and up to 30 days of authors, so every
    relay a member uses links those days;
  - the publisher's connection crosses midnight;
  - anyone can follow a once-a-minute rhythm that stops under one key at 23:59 and starts under another at 00:00,
    while few OGS streams exist;
  - kind 31078 itself marks the traffic as OGS.
- *Why it matters:* the privacy claims are the protocol's main selling point. They must be exact.
- *Suggestion:* state "by content, not by behaviour". Keep the construction, which still pays off for anti-overwrite,
  filtering and removed members.
- **Accepted** (text only): S§2, S§3, S§12, N§1, rationale D3, README; the 24b comparison row.

**M2. `ended` and grants were ordered in one direction only.**
- *Where:* C§9.2, C§10.2; `member._on_wrap`.
- *What:* a grant older than the last `ended` was ignored, but any `ended` applied, whatever its time. Relays
  re-deliver wraps after a restart (when the seen-id set is lost) and in any order. After a pause and resume
  (C§11.7: remove, then re-add), an old `ended` arriving after the new grant ends the member for good.
- *Why it matters:* a follower silently stops getting glucose.
- *Suggestion:* order both by rumor time; on equal times `ended` wins (fail safe).
- **Accepted:** C§10.2 (3), S§7; the reference; the vector step `ended-F-stale` → `ignored:ended-before-grant`; a
  test.

**M3. Emergency (auto-approved) invites could carry `ack`.**
- *Where:* C§8.7 forbade only `write` and `manage`.
- *What:* anyone who photographs the card joins automatically. With `ack`, that person could acknowledge alerts.
  D§8.3 lets publishers stop repeating an acknowledged alert, so a stranger could silence the alerts that carers
  rely on.
- *Suggestion:* `auto` invites MUST have no scopes, and administrators refuse to create one that has any.
- **Accepted:** C§8.7, conformance; `Owner.invite` refuses; a test.

**M4. Older epochs can't be authenticated once their membership expires from relays.**
- *Where:* C§9.2 (the grant carried only the current epoch's membership), C§9.3, C§7.3 (membership envelopes
  expire after 30 days).
- *What:* a member with a long window (a clinician's 90 days) gets covers for older epochs, but authenticating their
  publishers needs those epochs' memberships. These were published while each epoch was current, and expired 30
  days later. History republished under an old epoch (C§9.4) is then `unknown-author`, and the 90-day use case of
  R6 fails.
- *Suggestion:* every epoch in a grant carries its newest membership. That also delivers the old epoch's last
  membership privately, which C1 needs.
- **Accepted:** C§9.2; the reference; the vectors (the grant plaintexts).

### Minor

**m1. The invite fingerprint was 64 bits against a key that lives for years.**
- *Where:* C§8.1, C§8.3.
- *What:* the text commits to `I` by 8 bytes. Grinding a matching x-only key costs about 2⁶⁴ EC operations: decades
  on one GPU, weeks on a large cluster. `I` lives as long as the stream, so the grinding is a one-time cost per
  target. It is then reusable against every invite that the attacker sees, including months-long emergency cards.
  The payoff is impersonating the wearer to a joiner, and feeding a carer made-up glucose.
- *Suggestion:* 12 bytes of fingerprint and 12 bytes of secret. The text stays 34 characters, so the QR code doesn't
  grow, and both values get 96 bits.
- **Accepted:** C§8.1, C§8.3; `control.FP_BYTES`, `SECRET_BYTES`; the vectors; the tests.

**m2. Merge ties weren't deterministic.**
- *Where:* C§10.5 ("all replicas converge whatever order").
- *What:* two devices publishing `settings` with the same `rev`, or two series with the same `created_at`, resolved
  by arrival order (the reference kept the first, or the last, depending on the type).
- *Suggestion:* ties go to the greater record `id`. Memberships never reuse a `seq`.
- **Accepted:** C§10.5; the reference; a test that feeds both orders.

**m3. Puncturing contradicted the edit window and history.**
- *Where:* C§11.4 (puncture to today − 2), N§4.1 (watch 30 days for edits), C§9.4.
- *What:* a member that follows both RECOMMENDED values can't decrypt the edits or the history republications it
  subscribes to.
- *Suggestion:* one **edit window** (RECOMMENDED 7 days) for both. Puncture only after backfilling or requesting
  history.
- **Accepted:** C§11.4, C§9.4, N§4.1, conformance.

**m4. Re-subscriptions re-downloaded everything.**
- *Where:* N§4.1 ("no `since` on the stream filter").
- *What:* every reconnect, membership change and UTC midnight pulls all stored objects again, about 200 live blocks
  and up to 30 day archives of 25 KB, per relay. That is a real cost in battery and data on a phone.
- *Suggestion:* `since` = the newest `created_at` received − 60 s on re-subscription. Split long author lists, since
  relays cap them.
- **Accepted:** N§4.1, conformance.

**m5. The inbox `since` (now − 3 days) loses grants.**
- *What:* a member that was offline for a week misses its grant (the TTL is 30 days) or an `ended`.
- *Suggestion:* the last connection − 2 days (NIP-59 backdating).
- **Accepted:** N§4.1.

**m6. `days` was cut at the UTC boundary.**
- *Where:* C§8.3, C§11.1 (`to` = approval day + `days` − 1).
- *What:* a "1 day" grant approved at 23:50 UTC lasted 10 minutes, and a babysitter in UTC+2 approved at 23:00
  local time lost access at 02:00.
- *Suggestion:* `to` = approval day + `days`, so access lasts at least `days` × 24 h.
- **Accepted:** C§8.3, C§11.1; `Owner.approve`.

**m7. History requests were unbounded.**
- *Where:* C§9.4. The reference also didn't check the member's `to`.
- *What:* one request could make the administrator republish 1 000 day archives (about 25 MB).
- *Suggestion:* at most 31 days per request, inside `from … to`.
- **Accepted:** C§9.4, S§8; `Owner.on_wrap`; a test.

**m8. The epoch of a past-day record was unspecified.**
- *Where:* C§6.3, C§7.1.
- *What:* an edit of an entry from 40 days ago, after a rotation: under which epoch is it published?
- *Suggestion:* the current epoch if its range contains the day; otherwise the newest epoch that contains it and
  hasn't ended before it.
- **Accepted:** C§7.1.

**m9. The owner backup had a constant `d = "backup"` and one key for years.**
- *Where:* C§13.
- *What:* anyone could list every OGS administrator, and relays could link one administrator across months.
- *Suggestion:* the key changes every 30 days, and the `d` tag is opaque, derived from `I`. A new phone checks four
  periods.
- **Accepted:** C§13 (it is still OPTIONAL, with no vectors).

**m10. `manage` remove could name a device or `I`.**
- *Where:* C§11.9.
- **Accepted:** members only.

**m11. Grant size wasn't bounded.**
- *What:* NIP-44 limits each layer of NIP-59 to 65 535 bytes, and a grant now carries memberships (M4).
- **Accepted:** C§9.2 says about 45 KB, or several grants (they add).

**m12. An active stranger can link one phone within a day by its GATT UUIDs.**
- *Where:* B§9.
- *What:* a stranger who connects twice sees the same day UUIDs, across address and advertisement rotation.
- **Accepted as documentation** in B§9. Per-window GATT UUIDs are **rejected**: re-registering every 15 minutes drops
  every connected member, and a stranger who connects already sees the GAP name anyway.

**m13. Wraps a member sends over Bluetooth to a non-administrator publisher.**
- *What:* the spec said nothing about them.
- **Accepted:** B§6 says that publisher forwards them unchanged to the relays.

**m14. Adding a publisher device from another app has no interoperable channel.**
- *Where:* C§11.6.
- *What:* a third-party watch can't become a publisher.
- *Suggestion:* reuse the invite and join, with a grant whose cover is the root node.
- **Decided 2026-10-19: not in draft 3** (was Deferred). Only the wearer's own devices, added by the
  administrator, hold epoch roots and publish. Third-party apps and devices are members (readers, or contributors
  with their own key), never publishers, and an administrator MUST NOT make a key learnt through an invite, `join`,
  `link` or `manage` a publisher (C§11.6, conformance A, rationale D16).

**m15. Nearby join: should the administrator pick the join that matches the typed code?**
- *Where:* B§7, C§8.6.
- **Rejected:** the first-claim rule is already safe: a wrong first claimer fails the code, and the wearer retries.
  Picking among many joins would let an attacker send 10⁵ joins and match a 6-digit code about 10% of the time.

**m16. The FHIR bridge: `valueQuantity.comparator` for HI/LO, and the summary LOINC codes.**
- *Where:* D§11.1, D§11.3.
- *What:* not checked against the CGM IG 1.0.0 text in this review (offline).
- **Deferred:** check them before the spec is published. The mapping is informative.

### Editorial

**e1. The blinding didn't name its library equivalents or nonce rules.**
- **Accepted:** C§5.3 now has implementation notes:
  - the libsecp256k1 and rust function names;
  - the negate-then-add fallback;
  - the tweak ≥ n difference;
  - BIP-340 nonces over the blinded secret.

  This is what makes it implementable on Android (secp256k1-kmp) and iOS (swift-secp256k1).

**e2. S§3 didn't say that kind 31078 marks the traffic as OGS** (and so reveals how many streams exist).
- **Accepted.**

**e3. Emergency invites need the administrator online, and their rate limit can be used up by whoever holds the card.**
- **Accepted:** C§8.7, S§8.

**e4. "A cover has at most 2·DEPTH nodes" is loose** (the tight bound is 2·DEPTH − 2).
- **Rejected:** it is a correct upper bound, and changing it helps nobody.

## Other checks, no finding

- **Tree and day material:** HMAC-SHA256 as the PRG, and HKDF-Expand with the leaf as PRK, are standard. The labels
  are domain-separated. The content key used directly as the NIP-44 conversation key is uniform (NIP-DA does the
  same).
- **Replay:** records are addressable and versioned, wraps are deduplicated, joins never re-admit, and invites carry
  an absolute expiry.
- **MITM on join:** with m1, the fingerprint plus the seal signer authenticate the inviter. The match code binds `M`
  to the token.
- **DoS by relays** (withholding) is unavoidable and documented; three relays and Bluetooth mitigate it.
- **The data model** maps cleanly to Nightscout (39/401, directions, `utcOffset`), Tidepool (`cbg`, out-of-range
  annotation, `timezoneOffset`), Health Connect and HealthKit. Its gaps (pump and AID, ketones) are declared.
  Integer mg/dL and 18.01559 are the right choices.
- **Implementability:** every primitive is in mainstream libraries (NIP-44 v2 with a given conversation key is in
  nostr-tools, rust-nostr and Quartz; x-only tweaks in libsecp256k1). The protocol is about 1 event a minute per
  publisher, which is fine.

## What changed in this branch

| Area | Change |
|---|---|
| Core | C§5.3 notes; C§7.1 which epoch; C§8.1/8.3 12+12-byte invite; `days` semantics; C§8.7 read-only auto invites; C§9.2 per-epoch membership + `end` + size; C§9.4 history cap; C§10.2 ordering and epoch end; C§10.3 rights from the newest membership; C§10.5 ties; C§11.2 last membership, `epoch_end`, cut; C§11.4 edit window; C§11.9; C§13 backup |
| Nostr | N§1 honest linkability; N§4.1 edit window, `since`, author limits, inbox `since` |
| Bluetooth | `INFO.challenge` + `AUTH`; nothing before `AUTH`; pairing service carries no envelope; wrap forwarding; B§9 |
| Security | S§2, S§3, S§4, S§6, S§7, S§8, S§12 |
| Others | conformance, rationale (D3 limit, open item), README, migration, `spec/drafts/standalone-README.md` and `outreach.md` (counts), 24b (figures, comparison rows) |
| Reference | `core.Epoch.clip`; `member` (epoch end, grant format, ordering, ties); `owner` (`end_epoch`, frozen memberships, grants, history cap, auto invites, `days`); `control` (invite sizes); `ble` (`AUTH`); `vectors`; `e2e` (removed writer) |
| Vectors | regenerated: 44 events, 10 wraps, a 42-step scenario, BLE `AUTH` |

The site pages (`spec/drafts/site-page.md`, `docs/ogs.md`) make no claim that changed ("the keys that relays see
change every day" is still true), so they are unchanged.

## Status

- `uv run ogs d3 vectors verify`: **PASS** (44 events, 10 wraps, 5 covers, 3 invite checks, 3 owner checks, BLE at 3
  MTUs, 42-step scenario).
- `uv run python -m unittest discover tests`: **36 tests OK** (7 new).
- `uv run ogs d3 e2e` on nos.lol, relay.primal.net and nostr.mom: **9/9 PASS** in about 50 s, including "removed
  writer F2 can't write into its old epoch" (`ignored:unknown-author`).

## Decisions

Decided on 2026-10-19 after the review, and recorded in the spec:
1. **Removed keys' late records** (C1): **the rule stays.** Once a member holds the old epoch's last membership,
   it rejects every record of that epoch signed by a removed key, whenever it was signed. The trade-off is that a
   member that was offline loses the removed carer's last entries. A signed list of accepted record ids in
   `epoch_end` was considered and rejected for simplicity (C§10.3, S§6, S§12, rationale D15).
2. **Device enrolment for other apps** (m14): **not in draft 3.** Only the wearer's own devices publish.
   Third-party apps and devices are members only, and administrators MUST NOT make them publishers (C§11.6,
   conformance, S§12, rationale D16).
3. **The invite format** changed (12+12 bytes, same length). No app speaks draft 3, so nothing breaks. The
   standalone repository has been rebuilt.

## In Sukrara

Nothing. This is a review of a spec that no app speaks yet.
