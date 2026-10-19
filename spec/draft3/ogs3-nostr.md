# OGS3-N — Open Glucose Stream draft 3 over Nostr

`draft 3` · 2026-10-19 · [index](README.md) · core: [ogs3-core.md](ogs3-core.md)

How envelopes and control messages travel through ordinary public Nostr relays: kinds, filters, relays, AUTH,
retention, and what never to publish. It adds no cryptography beyond the core: NIP-01, NIP-40, NIP-42 and NIP-59
are used as they are, NIP-44 v2 with a symmetric conversation key, and NIP-70 and NIP-77 optionally.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY and OPTIONAL are to be
interpreted as described in RFC 2119 and RFC 8174 when, and only when, they appear in all capitals.

## N§1 What a relay sees

```
kind 31078   pubkey = a key that changes every day per publisher and lane    tags: d (opaque), expiration
             content = NIP-44 ciphertext, padded                              created_at = publish time
kind 1059    pubkey = one-time key                                            tags: p (an inbox key), expiration
             content = NIP-44 ciphertext                                      created_at = up to 2 days in the past
```

No identity key, device key or member key ever appears in clear. Nothing in an envelope is shared with another day's
envelopes. Relays can still count events, measure sizes and timing, and see the IP addresses of clients
([ogs3-security.md](ogs3-security.md) S§3).

## N§2 Kinds and tags

| Kind | Range | Published | Use |
|---|---|---|---|
| `31078` | addressable | yes | envelope (C§7) |
| `4078` | regular | **never** | record (C§6), only inside an envelope or a grant |
| `4079` | regular | **never** | control rumor (C§9), only inside a gift wrap |
| `1059` / `13` | NIP-59 | wrap yes, seal never | control messages |

On 2026-10-19 these kinds were free in the README table and `schema.yaml` of `nostr-protocol/registry-of-kinds` and
`nostr-protocol/nips`. Draft 1 used `21078`/`21079`/`30078`, and `31078`–`31080` were only proposed. Draft 3 reuses
`31078` with a new meaning; nothing ever shipped on it.

Kinds to avoid in extensions:
- `30078`/`78` (NIP-78: relays SHOULD serve them only to their author, which starves followers);
- `1351`–`1399`/`2351`–`2399` (proposed by the open health-metrics PR nips#1937);
- `30440`, `30443` (NIP-DA and Marmot).

Tags of an envelope: `d`, `expiration`, optional `-`. Tags of a wrap: `p`, `expiration`. Publishers MUST NOT add
others (client tags, `alt`, hashtags): every extra tag is metadata.

## N§3 Publishing envelopes

### N§3.1 Where

To every relay in the membership's `relays` (N§7). RECOMMENDED: three relays.

### N§3.2 How

- `created_at` strictly increasing per object (C§7.1). The live block is republished on every reading: about one
  event a minute per publisher while a sensor runs, plus a few a day for state, entries and alerts.
- Leave at least one second between events to the same relay. Some relays ban bursts. A backlog after a gap goes out
  oldest first, at that pace. An alert jumps the queue.
- On `OK false` with `rate-limited:`, retry later. On `invalid:` or `blocked:`, try the other relays. Never retry
  with another key.

### N§3.3 Protected events (OPTIONAL)

A publisher MAY add NIP-70's `["-"]` tag and authenticate (NIP-42) as the blinded author key before publishing. A
relay that honours NIP-70 then refuses copies of the envelope from anyone else. That stops a member from replaying a
publisher's envelopes to other relays, which it could do but has little reason to. The cost is that the relay must
support AUTH. Members MUST accept envelopes with or without the tag.

## N§4 Subscriptions

### N§4.1 Members

```
inbox:   {"kinds":[1059], "#p":[<member inbox>], "since": now − 3 days}
stream:  {"kinds":[31078], "authors":[ author(P, lane) for every day held in the window,
                                       for every (publisher, lane) in the membership ]}
```

- The **window** is the days to watch: RECOMMENDED yesterday, today and tomorrow (for midnight and clock skew) for the
  live view, and the last N days for entries that can still be edited (RECOMMENDED N = 30). Days outside the member's
  grant are skipped: their authors can't be computed.
- Publishers per epoch: `I` in lane `m`; each device in lanes `g` and `r`; each member with a scope in lane `r`
  (C§5.3). With 2 devices and 2 writers, a 3-day window is 21 authors per epoch.
- Re-issue the subscription when the membership changes, when a grant arrives, and at UTC midnight.
- No `since` on the stream filter: addressable events are few (about 100 a day per device), and relays return only
  the newest version of each.

### N§4.2 Backfill

The same `stream` filter over more days returns every object still on the relays. A member that wants 30 days of
entries without 30 days of glucose subscribes to the lane-`r` authors only. A member that needs days that relays no
longer keep sends a `history` request (C§9.4). The administrator then republishes those days as day archives.

**NIP-77 (OPTIONAL).** Negentropy reconciliation over the same filter lets a member that already holds most objects
fetch only the missing ones. strfry and nostream implement it. A rust-nostr relay census sample showed about one
relay in six advertising it (not verified here).
It helps most for a clinician catching up on 90 days. A plain REQ always works.

### N§4.3 Administrator

```
inbox:   {"kinds":[1059], "#p":[<administrator inbox of the current and recent epochs>, <every open invite's inbox>]}
stream:  {"kinds":[31078], "authors":[ the lane-r authors of members with a scope, for the days their records may be on ]}
```

## N§5 Control messages on relays

- Wraps go to the recipient's relays: the invite and join to the invite's relays (N§6); everything else to the
  membership's relays.
- Every wrap carries an **absolute** NIP-40 `expiration` (C§9.1 TTLs). NIP-59 backdates `created_at` by up to two
  days, so an expiration computed from it would arrive already expired.
- **AUTH (NIP-42).** Some relays serve kind 1059 only to the `p`-tagged key after AUTH, as NIP-59 recommends.
  Clients MUST answer such a challenge by signing kind 22242 with the inbox key the subscription is for (the member's
  inbox, the administrator's inbox, or the invite key). They MUST NOT authenticate with `I`, `D`, `M` or any key of
  the user's own Nostr identity.
- Kind 21059 (ephemeral gift wrap, NIP-59) is not used: every control message must survive the recipient being
  offline.

## N§6 Invites on relays

- The administrator publishes the invite wrap to the hint relays of the invite text (C§8.1). If the text has no
  hints, it publishes to the **lookup relays**: `wss://nos.lol`, `wss://relay.primal.net`, `wss://nostr.mom`,
  `wss://relay.snort.social`.
- The joining app subscribes with `{"kinds":[1059], "#p":[invite_key.pub]}` on the hints, then the lookup relays,
  answering AUTH as the invite key.
- The join goes to the invite message's `relays`.

The lookup list is a convenience, not trust: the fingerprint and the seal authenticate the invite. Apps SHOULD put
hints in every invite they create.

## N§7 Relay requirements

A relay is usable for OGS when it:
1. accepts events from keys it has never seen (every envelope author is new each day, and every wrap is signed by a
   one-time key);
2. serves kind 31078 to anyone who asks by `authors`;
3. serves kind 1059 to the `p`-tagged key (after AUTH if it requires it);
4. ideally honours NIP-40, though nothing depends on it, because receivers drop expired events themselves.

Relays that rate-limit by web of trust or follower count fail (1) after a few events. Relays whose AUTH can't serve
wraps fail (3). Measured:
- 2026-10-17 (draft 1): `nos.lol`, `relay.primal.net`, `nostr.mom` and `relay.snort.social` qualified;
  `relay.damus.io` and `relay.nostr.net` couldn't serve wraps; `offchain.pub` refused new keys after about 8 events.
- 2026-10-19 (draft 3 live run): `nos.lol`, `relay.primal.net` and `nostr.mom` accepted and served every envelope
  and wrap.

## N§8 What never to publish

These rules keep a glucose stream from being linked to a person (S§10). Implementations MUST NOT:
- publish kind 0, 3, 10002 (NIP-65), 10050 (NIP-17 inbox relays), 10051 or any other public event signed by `I`,
  `D`, `M`, an inbox key or a blinded key;
- use the user's own Nostr key as `I`, `D` or `M`, or sign anything for the stream with it. A main key MAY be used
  once to derive `I` one-way: `derive_scalar(main.sec, "nostr/derived-account/v1", app + "#" + index)`, as in the open
  NIP-PNS idea (nips#1893);
- publish a record (kind 4078) or a control rumor (kind 4079) outside its envelope or wrap;
- put a profile, a name or a picture URL anywhere except inside encrypted payloads.

## N§9 Phones in the background (informative)

Android followers keep their relay subscriptions in a foreground service. iOS doesn't let an app keep websockets
open in the background, so iOS followers need a push service: a server that holds the member's `stream` filter and
wakes the app through APNs when a matching event arrives. Such a server learns the filter: the blinded authors of the
days it was given. These link the member's subscription across those days for that server, but not to the wearer,
and nothing of the content. Give a push server one day at a time, and use one that keeps no logs. Marmot's MIP-05 is
prior art for privacy-preserving push over Nostr. Draft 3 doesn't standardise a push service (rationale, open
items).

## N§10 Erasure

To withdraw a stream from relays before it expires, the administrator and each publisher, for every day still on
relays:
- publish a NIP-09 kind 5 deletion signed by each of their blinded authors, listing the `a` coordinates
  (`31078:<author>:<d>`) of their objects;
- MAY publish a NIP-62 kind 62 request to vanish for each blinded author.

Relays may ignore both. Expiration remains the dependable mechanism, which is why it's short.
