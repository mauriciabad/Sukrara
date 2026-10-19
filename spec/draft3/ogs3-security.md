# OGS3-S — Open Glucose Stream draft 3: security and privacy considerations

`draft 3` · 2026-10-19 · [index](README.md) · core: [ogs3-core.md](ogs3-core.md)

The threat model, the properties the protocol provides and how, what still leaks, and the normative privacy rules.
Section S§10 is normative; the rest explains.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY and OPTIONAL are to be
interpreted as described in RFC 2119 and RFC 8174 when, and only when, they appear in all capitals.

## S§1 Assets and adversaries

**Assets:**
- the glucose values, treatments, alerts and sensor state (special-category health data);
- **the link between a real person and a glucose stream**: even "this person has diabetes" is health data;
- who follows whom;
- the wearer's control over who can read.

| # | Adversary | Capabilities |
|---|---|---|
| A1 | onlooker (anyone reading public relays) | sees every published event |
| A2 | relay operator | sees events, timing, client IPs, subscriptions, AUTH keys; can drop, delay, replay, reorder; cannot forge signatures |
| A3 | removed member | had full read access until removal; knows the old epoch |
| A4 | current member acting badly | can read what its grant covers; can publish under its own blinded key; can leak what it reads |
| A5 | invite-link interceptor | holds an unused invite text (a forwarded message, a photographed QR code) |
| A6 | nearby radio observer | passive scanning and active connection attempts |
| A7 | thief of a device | a member's, a publisher's or the administrator's device, with its keys |

Out of scope: a compromised operating system on an active device (it sees what the app sees), and traffic-analysis
adversaries who watch the wearer's network directly (use Tor).

## S§2 Properties

| Property | How | Holds against |
|---|---|---|
| Confidentiality of content | NIP-44 v2 with day keys only grant holders can compute (C§5) | A1, A2, A3 after rotation, A5, A6 |
| Authenticity, integrity | every record signed by its author; authority chain `I` → membership → devices and contributors (C§10) | everyone; a member can't impersonate the wearer or another member |
| Unlinkability to a person | stream keys never publish anything public; no profile, serial or account id on the wire (S§10, D§1) | A1, A2, A6 |
| Unlinkability over time | blinded authors and `d` tags change daily; inbox keys per epoch and per invite; BLE UUIDs every 15 minutes and every day (C§5.3, B§3) | A1, A2, A6 across days; within a day, events link |
| Consent | explicit human Allow, a typed code for nearby joins, an offer shown before joining, single-claim invites (C§8) | A5 |
| History scope | a cover starts at the agreed first day (C§4.4) | A4 can't read before its window |
| Time-limited access | a cover ends on the agreed day, with no action needed (C§11.3) | A4 after the end |
| Revocation | rotation: new tree, new authors, new inboxes, new BLE identifiers (C§11.2) | A3 for everything published after it |
| Forward secrecy (relay-stored data) | members puncture their covers (C§11.4) | A7 on a member device: relay copies of punctured days stay closed |
| Post-compromise security | scheduled rotation every ≤ 30 days (C§11.5) | an attacker with leaked seeds but no lasting device access |
| Contributor scope | entries keyed by creator; only creator or wearer devices edit (C§10.5) | A4 can't change the wearer's entries |
| No relay-level overwriting | each publisher signs under its own blinded key | A4 can't overwrite another publisher's objects on relays |

## S§3 What leaks anyway

| To whom | What |
|---|---|
| relays and onlookers | that some participant publishes about once a minute (a CGM-like rhythm), event sizes in 256-byte steps and NIP-44 buckets, the number of publishers per day (distinct authors), when lane-`r` records appear (possibly treatment or alert times; the 15-minute `device` refresh masks them only partly), and when wraps are sent |
| relays | client IP addresses; which authors each connection subscribes to, so relays can group a member's subscription by day. A relay that sees the wearer's IP publishing and a member's IP subscribing to the same authors learns that those two IPs are linked. |
| a member | everything its grant covers; the publishers and contributors (keys, not names) in the membership; the wearer's profile |
| a removed member | what it could read before; that the stream continues (it sees relay traffic like anyone), but not which events are the stream's |
| a push server (N§9) | a member's filter for the days it was given |
| nearby | B§9 |

**Mitigations an app can add:**
- use a relay set that doesn't include the user's social relays;
- reach relays over Tor or a VPN;
- publish the live block on a fixed schedule (every minute, also during sensor gaps, with the same rows), so gaps
  don't reveal signal loss;
- keep the number of relays small.

Cover traffic beyond that is not specified.

## S§4 Key compromise

| Lost key | Effect | Recovery |
|---|---|---|
| a member's device (`M`, inbox, cover) | the attacker reads what that member could, until removal or the end of the cover; it can write within the member's scopes | remove the member (rotate) |
| a publisher device (`D`, epoch roots) | the attacker reads the epoch and can publish as that device | remove the device (rotate); the old `D` is no longer in the new membership |
| the administrator (`I`) | the attacker controls the stream: it can grant itself, remove others and rotate | none within the protocol; members must be re-invited under a new identity. Keep `I` in hardware-backed storage (Android Keystore, Apple Keychain/Secure Enclave), and its backup offline. |
| an epoch root alone (a backup leak) | reads that epoch | rotate |
| an unused invite text | the holder can ask to join; the wearer still decides, the code is checked, and the invite expires | let it expire, or forget the token |
| an emergency invite (C§8.7) | anyone can read for its window, and the wearer is notified | forget the token |

## S§5 Forward secrecy and post-compromise security, precisely

- **Within the protocol** a member device holds key seeds, and can derive day keys from them. Puncturing (C§11.4)
  deletes the seeds of past days, so a later theft of that device doesn't open relay-stored envelopes of those days.
  It doesn't protect the decrypted history the app chose to keep. Apps SHOULD encrypt their local database with a
  device-bound key, and SHOULD offer a limit on how long history is kept.
- **The administrator and publisher devices** hold epoch roots: they don't get forward secrecy against their own
  compromise. They hold the plaintext anyway.
- **PCS** comes from the next rotation. The administrator generates the new root with fresh randomness and delivers
  it only to the remaining members, over NIP-44 to each member's inbox.
- **MLS (RFC 9420)** would add PCS per member update and per-message FS through its secret tree. It needs commits
  delivered in order, which public relays and Bluetooth don't guarantee, and it needs an MLS stack on every device.
  With one administrator and few readers, OGS gets the same practical properties from covers, puncturing and
  rotation. See rationale D2.

## S§6 Members as insiders

A member is trusted to read, not to be honest. It can:
- leak what it reads (no protocol prevents screenshots);
- publish garbage under its own blinded key: it is rejected by the inner-signature, lane and scope checks, and costs
  receivers one decryption;
- with `write`, add false entries attributed to itself: they are signed by it, and the wearer sees who wrote them;
- republish old envelopes (replay): they deduplicate and merge by version, so a replay changes nothing.

It cannot:
- impersonate the wearer, a device or another member;
- change another participant's entries;
- overwrite another publisher's objects on relays;
- recognise or read anything after its removal or beyond its cover;
- learn who the readers are.

## S§7 Time, replay and ordering

- Signed `created_at`, `rev` and `seq` order versions. A replayed older version loses the merge (C§10.5).
- Alerts older than 10 minutes never notify (D§8.2).
- The rumor time of a gift wrap is chosen by its sender. The administrator uses it only to rate-limit, and members
  only to order a grant against an `ended`.
- Day assignment uses the publisher's clock. A publisher whose clock is a day off publishes under the wrong day's
  keys. Members of a time-limited grant would then miss those records, which fails safe.

## S§8 Denial of service

- Relays can drop or withhold events. Publish to three relays; Bluetooth works without any.
- Anyone can flood a relay with kind 31078 events. Members filter by author, so only the stream's own blinded keys
  reach them.
- A member can flood its own lane. The administrator removes it.
- Bluetooth limits: B§8.

## S§9 Safety

OGS moves numbers. It is not a medical device, and nothing in it is validated for treatment decisions.
- A follower's copy can be late or missing. Apps MUST show the age of the newest reading, and SHOULD alert on
  missing data rather than assume silence means safety.
- `lo`, `hi`, `unreliable`, `warmup` and `interpolated` readings MUST NOT be presented as exact or trustworthy values
  (D§4.2).
- Data from OGS SHOULD NOT feed automated insulin delivery. A bridge to such a system MUST say that the data is
  relayed.

## S§10 Privacy rules (normative)

1. Stream keys (`I`, every `D`, every `M`, inbox keys, blinded keys) MUST NOT sign or publish anything outside this
   protocol, and in particular no kind 0, 3, 10002, 10050 or any other public event (N§8).
2. A user's own Nostr key MUST NOT be a stream key, MUST NOT sign anything for the stream, and MUST NOT authenticate
   to a relay in the same session as stream keys. It MAY derive `I` one-way.
3. Profiles, names and pictures exist only inside encrypted payloads, and pictures only as renderer parameters
   (D§10).
4. No sensor serial, transmitter id, account id, phone model or location anywhere in a record (D§1).
5. AUTH only with the inbox or invite key the subscription is for (N§5).
6. An app MUST NOT send OGS data to a third party (a bridge, a push server, an analytics service) without the user's
   explicit consent for that destination.

## S§11 Health-data regulation (informative)

In the EU, glucose data is special-category data (GDPR art. 9). OGS is designed so that an app can meet the usual
duties without a server:
- **consent:** explicit, per member, revocable, with the offer shown first;
- **minimisation:** no identifiers, short relay retention, history windows;
- **erasure:** expiration and N§10;
- **security:** end-to-end encryption.

Relays are not processors that can read the data, but they do see metadata (S§3). App makers remain responsible for
their own apps, and should get legal advice.

## S§12 Known limitations

- Metadata: S§3. There is no cover traffic and no mixing.
- No PCS for a compromised administrator.
- No cryptographic hour-level windows. Grants are whole UTC days, so "school hours" is a policy, not a key.
- Removal needs the administrator online, and a grant per remaining member.
- Bluetooth: Android's GAP name and unsynchronised rotation (B§9).
- An iOS follower in the background depends on a push service (N§9).
