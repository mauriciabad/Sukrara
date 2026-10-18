# OGS-D — Open Glucose Stream: data model

`draft 1` · wire version 2 · 2026-10-18 · [index](README.md) · transports: [Nostr](ogs-nostr.md), [Bluetooth](ogs-ble.md)

This document defines the JSON objects an OGS stream carries: glucose readings, hourly history, sensor life,
treatments, alerts and profiles. They're the same on every transport. How they are signed, encrypted, addressed and
delivered is in [ogs-nostr.md](ogs-nostr.md); the Bluetooth link ([ogs-ble.md](ogs-ble.md)) carries those same
signed events unchanged.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY and OPTIONAL are to be
interpreted as described in RFC 2119 and RFC 8174 when, and only when, they appear in all capitals.

## D§1 Conventions

- **JSON.** Every object is UTF-8 JSON (RFC 8259). Producers SHOULD write it compactly. Receivers MUST NOT depend
  on key order, whitespace or the escaping of `/`. Receivers MUST ignore keys they don't know (that is how the
  format grows, see [D§10](#d10-versioning-and-extensibility)).
- **Numbers.** JSON has one number type. A field called *integer* MUST be written without a fraction or exponent.
  Receivers SHOULD accept an integral value written as `5.0`. Some JSON libraries (Android's `org.json`) write a
  whole double as `5`. A field called *number* accepts both forms.
- **Absent and null.** A field marked *optional* may be left out. A field marked *nullable* may be `null`. Unless a
  field says otherwise, receivers MUST treat a missing optional field and a `null` the same way.
- **Time.** Every time is a **Unix timestamp in whole seconds** (UTC), except the treatment version `u`, which is in
  **milliseconds**. An *hour start* is a timestamp divisible by 3600, so hours are UTC-aligned.
- **Glucose** is always an **integer in mg/dL**, on every transport. There is no unit field and no mmol/L on the
  wire. Converting for display is the receiving app's business. A plausible value is 1–1000; receivers MUST reject
  anything outside that range.
- **Rate of change** is a **number in mg/dL per minute**, positive when rising. It's `null` when the source has no
  rate (Libre 3 history, Juggluco without a direction).
- **Version field `v`.** Every top-level object carries an integer `v`. Draft 1 values: `2` for readings, chunks,
  sensor updates, rosters, alerts and private messages; `3` for invites; `1` for treatments and avatars. A receiver
  MUST drop an object whose `v` it doesn't implement. Proposal [P7](PROPOSALS.md#p7) aligns these numbers.

## D§2 Trend

The rate is the trend. OGS sends no arrow names. To talk to systems that want an arrow, map the rate like this
(Nightscout/Dexcom directions):

| rate `r` (mg/dL/min) | direction |
|---|---|
| `r ≥ 3` | `DoubleUp` |
| `2 ≤ r < 3` | `SingleUp` |
| `1 ≤ r < 2` | `FortyFiveUp` |
| `−1 < r < 1` | `Flat` |
| `−2 < r ≤ −1` | `FortyFiveDown` |
| `−3 < r ≤ −2` | `SingleDown` |
| `r ≤ −3` | `DoubleDown` |
| `null` | `NONE` |

LibreView's five arrows use other limits: `r > 2` RisingQuickly, `r > 1` Rising, `r ≥ −1` Stable, `r ≥ −2` Falling,
otherwise FallingQuickly (`null` → Undetermined).
The Nightscout `delta` field is `r × 5` (mg/dL per 5 minutes).

## D§3 Reading (live)

The newest reading, sent the moment the source has it (about once a minute for a Libre 3).

```json
{"v":2,"t":1792087170,"sgv":123,"r":-0.42,"dev":"libre3","ss":1791828000,"se":1793124000,"sw":1791831600}
```

| key | type | meaning |
|---|---|---|
| `t` | integer, required | when the sensor measured it (s) |
| `sgv` | integer, required | glucose, mg/dL |
| `r` | number, nullable | rate, mg/dL/min. Producers SHOULD round it to 2 decimals. |
| `dev` | string, optional | free-form label of the source (`libre3`, `juggluco`, `synthetic`, `""`). Informative only: receivers MUST NOT make decisions on it. |
| `ss`, `se`, `sw` | integer, optional | the sensor (§5) |

- A producer MUST NOT send a reading older than 10 minutes as a live reading (send it in the hour chunk instead), and
  SHOULD send each reading once. Receivers MUST reject a `t` more than 5 minutes in the future.
- Receivers keep **one reading per `t`**. Draft 1 keeps the first copy it receives; see [P11](PROPOSALS.md#p11).

## D§4 Hour chunk (history)

All readings of one UTC hour, replaced as the hour fills up. This is how followers backfill.

```json
{"v":2,"h":1792083600,"dev":"libre3","r":[[0,118,-35],[60,117,null],[120,117,-12]]}
```

| key | type | meaning |
|---|---|---|
| `h` | integer, required | the hour start (divisible by 3600) |
| `dev` | string, optional | like the live `dev` (the hour's last reading) |
| `r` | array, required | rows `[dt, sgv, rate100]` sorted by `dt`: `dt` = `t − h` (integer, 0 ≤ dt < 3600), `sgv` integer mg/dL, `rate100` = round(rate × 100) as an integer, or `null` |

Libre 3 gives at most 60 rows an hour. Receivers MUST reject a row outside the ranges above, and MUST NOT
expect a fixed spacing (Libre 2 history is every 15 minutes, Dexcom every 5).

## D§5 Sensor life

The owner's current sensor, so followers can show its age, warm-up and end:

| key | type | meaning |
|---|---|---|
| `ss` | integer | sensor start |
| `se` | integer | sensor end: start + wear length (15 days for a Libre 3 Plus), or earlier if the sensor reported that it ended |
| `sw` | integer | end of the warm-up (start + 60 minutes for a Libre 3) |

- These fields travel inside a live reading (§3) or in a **sensor update**, a live payload with no reading:
  `{"v":2,"ss":…,"se":…,"sw":…}`. An owner MUST send a sensor update when its sensor changes or ends, and SHOULD
  repeat it every 5 minutes during the warm-up (there are no readings to carry it).
- A payload with `t` and `sgv` is a reading. One without them is a sensor update.
- Receivers take the sensor from the live payload with the **newest event `created_at`**, so an older copy from a
  backlog doesn't overwrite it. A payload **without `se`** means "no sensor known" and clears it. A missing `sw`
  means `ss + 3600`, and a missing `ss` means 0.
- **No serial number.** The start time identifies the sensor; a serial would link the stream to an Abbott account.
  Producers MUST NOT add one.

## D§6 Treatments

Insulin, carbs and notes, entered by the wearer or by a follower who is allowed to write (a *writer*).

```json
{"v":1,"id":"6f1c2b9a-0d3e-4c5f-8a7b-0123456789ab","k":"rapid","a":2.5,"t":1792086900,"n":"lunch","u":1792086910123,"del":false,"o":"phone"}
```

| key | type | meaning |
|---|---|---|
| `id` | string, required | UUID v4, lowercase, made once by whoever creates the entry. It never changes. |
| `k` | string, required | `rapid` or `long` (insulin, units), `carbs` (grams), `note`. It never changes after creation. |
| `a` | number | amount: insulin to 0.1 u, carbs in whole grams, `0` for notes |
| `t` | integer, required | when it happened (s) |
| `n` | string | free text, at most 100 UTF-8 bytes |
| `u` | integer, required | last change, **milliseconds**: the version |
| `del` | boolean | tombstone. A deleted entry is kept and published so the delete spreads. |
| `o` | string | where it was created. Draft 1: `phone`, `mac` or `follower`. Informative, but it breaks ties (§6.2). |

### D§6.1 Rules

- Receivers MUST reject an unknown `k`, an `id` that isn't a lowercase UUID, a negative `a`, and an `n` over 100
  bytes.
- Deletes are tombstones (`"del":true`, newer `u`). They are never dropped from the log while it's published
  (30 days).

### D§6.2 Merge

For an `id` held as `cur` and an incoming `inc`, the winner is `inc` if `(inc.u, inc.o) > (cur.u, cur.o)`, compared
lexicographically, and `cur` otherwise. An unknown `id` is inserted. Every replica converges whatever order the
entries arrive in.

Which authors may publish which entries is a transport rule (ogs-nostr §5.5 and §9.2).

### D§6.3 Mapping (informative)

| OGS | Nightscout treatment | Tidepool | LibreView |
|---|---|---|---|
| `rapid` | `{"eventType":"Correction Bolus","insulin":a}` | `bolus`/`normal` | `insulinEntries` `RapidActing` |
| `long` | `{"eventType":"Note","insulin":a,"notes":"long-acting"}` (Nightscout has no long-acting type; xDrip+ uses `insulinInjections`) | `insulin` (injected, long) | `insulinEntries` `LongActing` |
| `carbs` | `{"eventType":"Carb Correction","carbs":a}` | `food` (`nutrition.carbohydrate.net`) | `foodEntries` |
| `note` | `{"eventType":"Note","notes":n}` | — | `genericEntries` `customnote` |

`_id`/`identifier` = `id`, `created_at` = ISO 8601 of `t`.

## D§7 Alerts

The wearer's app tells followers every alert it raises or clears, so they can alert at the same moment and with the
same words. The alert rules are the owner app's own (Sukrara's are in `spikes/alerts/SPEC.md`). This section only
defines what goes on the wire.

```json
{"v":2,"type":"alert","id":"a1b2c3d4e5f60718","k":"high","state":"fire","at":1792087180,"t":1792087170,"sgv":192,"r":-0.4,"th":180,"n":2,"tr":1}
```

| key | type | meaning |
|---|---|---|
| `type` | `"alert"`, required | |
| `id` | string, required | random per episode (16 hex chars). A `clear` repeats the `id` it ends. |
| `k` | string, required | `very_low`, `low`, `high`, `very_high`, `extremely_high`, `falling_fast`, `rising_fast`, `no_data` |
| `state` | string, required | `fire` (posted, also each repeat) or `clear` (condition over: remove it) |
| `at` | integer, required | when the owner fired or cleared it |
| `t`, `sgv`, `r` | nullable | the reading behind it (`no_data`: the last one known, or null) |
| `th` | number, nullable | the threshold that matched: mg/dL for levels, mg/dL/min for rates, minutes for `no_data` |
| `n` | integer | 1 for the first alert of the episode, then 2, 3… |
| `tr` | integer | treatments the headline acknowledges: 0, 1, or 2 (two or more) |

- Receivers MUST reject an unknown `k` or `state`, MUST ignore an alert whose `at` is more than **10 minutes** old,
  and MUST deduplicate by `(id, state, n)`. An alert is a notification, not a record: it is never stored on relays
  for long (ogs-nostr §5.2).
- A muted alert (after insulin or carbs, see the owner's rules) is never sent.

## D§8 Profiles

A display name and a generated picture per person, so lists show faces instead of keys. Profiles travel **only inside
encrypted payloads** (the invite, access request, profile update, roster and owner backup). They are **never** published
as a Nostr kind 0 (ogs-nostr §12.1).

```json
{"name":"Alex","avatar":{"v":1,"c":0,"s":"cube","a":45,"n":1497950029}}
```

- `name`: a string of at most 40 characters. It is self-asserted: it helps people recognise each other and
  authenticates nothing.
- `avatar`: the parameters of a picture, never an image. `v` is the renderer version. A receiver that knows `v` draws
  it. One that doesn't MUST keep and forward the object unchanged, and SHOULD draw a fallback (a grey `#78909C`
  square with the name's first letter). Receivers MUST ignore an avatar object over 512 bytes of JSON. The `avatar`
  is nullable.
- Renderer **v1** (the reference implementation's): `c` 0–7 picks a gradient pair, `a` is the angle in degrees (a
  multiple of 15), `s` ∈ `cube|drop|dots|rings|letter` is the white shape, and `n` (0…2³¹−1) seeds `dots`. Geometry,
  colours and vectors are in Annex A. Implementing it is OPTIONAL. Without it, an app draws the fallback.

## D§9 Mapping to Nightscout entries (informative)

An OGS follower can serve a local Nightscout API (`/api/v1/entries.json`, `/api/v1/treatments.json`). That is the
cheapest way to feed the existing ecosystem: watch faces, GlucoDataHandler, AndroidAPS in follower mode.

```json
{"_id":"<first 8 hex of the stream's device key>-<t>","type":"sgv","sgv":sgv,"date":t*1000,"dateString":"<ISO 8601 Z>","direction":"<D§2>","delta":r*5,"device":"<dev>","noise":1,"units":"mg"}
```

On Android, a follower MAY also send the xDrip+ broadcast `com.eveningoutpost.dexdrip.BgEstimate`. Both bridges are
outside the conformance classes: they leave the encrypted channel, so the app MUST ask the user first.

## D§10 Versioning and extensibility

- **New optional keys** may be added to any object without a version change. Receivers ignore keys they don't know
  (D§1).
- **Breaking changes** (a key changes meaning, a required key is added or removed, units change) bump the object's
  `v`. A producer that changes `v` SHOULD keep publishing the old version alongside the new one until its followers have
  upgraded. Draft 1 has no capability negotiation; see [P8](PROPOSALS.md#p8).
- **Enumerations** (`k` of treatments and alerts, `s` of avatars) are closed in draft 1: unknown values are
  rejected, so a new value is a breaking change. [P9](PROPOSALS.md#p9) proposes "ignore and keep".

## Annex A — Avatar renderer v1

Drawn in unit coordinates (0…1, y down), then clipped to a circle by the UI.

- **Background:** a linear gradient from colour A to colour B along angle `a`, from `0.5 − (cos a, sin a)/2` to
  `0.5 + (cos a, sin a)/2`. The pairs for `c` 0–7 (ARGB hex): `1BB5C9→0B2E5E`, `C6F432→2E7D32`, `FF8A65→BF360C`,
  `BA68C8→4A148C`, `FFD54F→E65100`, `4FC3F7→01579B`, `F06292→880E4F`, `90A4AE→263238`.
- **Shapes** (white):
  - `cube`: three faces, w = 0.22, alpha 235/170/110.
  - `drop`: a circle r = 0.2 at (0.5, 0.62) plus a triangle up to (0.5, 0.2), alpha 220.
  - `dots`: five circles. Circle k takes bytes 4k…4k+3 of `sha256("sukrara/avatar/v1/dots:" + n)`:
    r = 0.08 + 0.12·b₀/255, x = 0.25 + 0.5·b₁/255, y = 0.25 + 0.5·b₂/255, alpha = 90 + ⌊110·b₃/255⌋.
  - `rings`: 4 concentric strokes, r = 0.09·i, width 0.03, alpha 60 + 40·i.
  - `letter`: the name's first character, uppercased, in a bold system font at size 0.5, alpha 240, glyph box
    centred.
- The vectors for `dots` are in `spikes/nostr/identity/avatar-v1.json`.
