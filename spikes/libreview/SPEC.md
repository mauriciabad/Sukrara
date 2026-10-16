# Treatments + LibreView upload — shared spec (investigation 04)

Both apps implement this. The phone (`glucobridge-android`) and the Mac (`menubar`) each keep a treatment log,
sync it over the existing BLE link, and either one can upload to LibreView. `lv.py` in this folder is the
executable reference: it generates `fixture.json`, which both apps' tests must reproduce byte for byte.

## 1. Treatment

One JSON object per treatment. The same shape is stored on disk, sent over BLE and (later) published to Nostr.

```json
{"id":"6f1c2b9a-0d3e-4c5f-8a7b-0123456789ab","k":"rapid","a":2.5,"t":1792067696,"n":"","u":1792068000123,"del":false,"o":"phone","lv":0}
```

| key | meaning |
|---|---|
| `id` | UUID v4, lowercase, created once by the device that creates the treatment. Never changes. |
| `k` | `rapid` \| `long` (insulin units) · `carbs` (grams) · `note` (text only). Immutable after creation. |
| `a` | amount: insulin with 1 decimal, carbs whole grams, `0` for notes |
| `t` | when it happened, epoch **seconds** (UTC) |
| `n` | free text (LibreView only carries it for `note`); ≤ 100 UTF-8 bytes |
| `u` | last modified, epoch **milliseconds** — the version for last-writer-wins |
| `del` | tombstone (deleted treatments are kept so the delete syncs and reaches LibreView) |
| `o` | device that created it: `phone` \| `mac` |
| `lv` | the `u` that was last confirmed uploaded to LibreView by either device (0 = never) |

**Merge** (receiving `inc` for an `id` we hold as `cur`):
`winner = inc if (inc.u, inc.o) > (cur.u, cur.o) else cur`; result = winner with `lv = max(cur.lv, inc.lv)`.
Changed if the result differs from `cur`. Unknown ids are inserted as-is.

**Needs upload** when `lv < u`. Upload policy (avoids both devices racing for the same row): the origin device
uploads immediately; the other device uploads only if the row still needs upload 2 minutes after it learned it.
Double uploads must be harmless anyway (same device identity + same `recordNumber`) — that is one of the
things this spike verifies.

**Nostr later**: a treatment maps to a parameterized-replaceable event (`d` = `id`, `created_at` ≈ `u`); the
merge rule above is the same rule relays apply. Nothing here depends on BLE.

## 2. BLE sync (GATT, phone = server, Mac = client)

New characteristic in the existing GlucoBridge service:
`TREATMENTS 6c1f0004-5b8a-4d8e-9a3c-676c75636f42` — write (with response) + notify (CCCD).
Each write / notification is **one** UTF-8 JSON object with an `op`. Every message fits one notification
(≤ MTU − 3 bytes; the phone truncates `n` if needed).

The phone numbers every accepted change with its own increasing `seq` (persisted). The Mac keeps a cursor.

| direction | message | meaning |
|---|---|---|
| Mac → phone | `{"op":"pull","since":42}` | send every record with `seq > 42` |
| phone → Mac | `{"op":"rec","s":43, …treatment}` | a record (reply to pull, or a live change) |
| phone → Mac | `{"op":"end","s":57,"dev":"<LibreView DeviceId or empty>"}` | end of a pull reply; `s` = phone's max seq |
| Mac → phone | `{"op":"rec", …treatment}` (no `s`) | an `lv` watermark after a Mac upload; the GATT write response is the ack, or ATT error `0x03` (write not permitted) when refused |

Flow on every (re)connect: Mac subscribes → writes all its un-acked `lv` watermarks → writes `pull` with its
cursor. Each `rec` it receives is merged and moves the cursor to `s`. If `end.s < cursor` the phone's store was
reset: set cursor 0 and pull again. The phone also notifies a `rec` for every change it stores (its own, v2
writers', `lv` updates).
The Mac stores `dev` and uses it as its LibreView DeviceId (so both apps are one LibreView device).

**Who may write** (Sukrara, investigation 10): the link is unauthenticated, so the phone can't tell one Mac from
another and the per-follower "Can add entries" (roster `writers`, §5) can't apply to it. The link is read-only for
entries: Sukrara for Android refuses every `rec` that adds an entry or changes one (`u` newer than its own copy,
deletes included) with ATT error `0x03`. A `rec` for a row the phone holds with `u` ≤ the phone's is taken, but only
its `lv` (LibreView watermark sync). Sukrara for Mac sends its entries over protocol v2 when it is a roster writer
(signed by its key) and otherwise disables "Log treatment…"; over this link it writes only `lv` watermarks.

## 3. LibreView (impersonates the FreeStyle Libre 3 Android app, like Juggluco's `newlibre3.cpp`)

**Config** (cache it): `GET https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Assets/3.3.0/DE.json`
→ `Configuration` (URL) → `GET` it → `newYuUrl`, `newYuApiKey`.

**Headers** on both calls below:
```
Content-Type: application/json
Platform: Android
Version: 3.3.0
Abbott-ADC-App-Platform: Android/16/FSL3/3.3.0.9092
Accept-Language: en-ES
x-api-key: <newYuApiKey>
x-newyu-token: <UserToken, or empty for auth>
```

**Auth** `POST {newYuUrl}/api/nisperson/getauthentication`
`{"Culture":"en-ES","DeviceId":"<uuid>","Password":"…","SetDevice":false,"UserName":"…","Domain":"Libreview","GatewayType":"FSLibreLink3.Android"}`
→ `{"status":0,"result":{"UserToken":…,"AccountId":…,"DateOfBirth":…,"FirstName":…,…}}`.
`status 20` + reason containing `wrongDeviceForUser` = another device is the account's active one. **Do not**
retry with `"SetDevice":true` unless the user enabled "take over" — that kicks the other app (Juggluco) off.

**Upload** `POST {newYuUrl}/api/measurements` with the payload below → `{"status":0}`.
`status 20` + `wrongDeviceInToken` → re-auth once and retry. Anything else non-zero → stop and report.

**Sensor registration** (phone, before the first glucose upload of a sensor): `PUT {newYuUrl}/api/nisperson`
(same headers) with `{"DateOfBirth":"…","DomainData":"{\"activeSensor\":\"<serial9>\"}","FirstName":…,"GuardianFirstName":…,"GuardianLastName":…,"LastName":…,"UserToken":…,"Domain":"Libreview","GatewayType":"FSLibreLink3.Android"}`
(values from the auth result).

### Payload — exact bytes

Compact JSON (no whitespace), keys in exactly this order, built by string concatenation so both apps match.
Strings are JSON-escaped (`"` `\` and control chars as `\uXXXX`, except `\n` `\t`).

```
{"DeviceData":{"deviceSettings":{"factoryConfig":{"UOM":"mg/dL"},"firmwareVersion":"3.3.0","miscellaneous":{"selectedLanguage":"en-ES","valueGlucoseTargetRangeLowInMgPerDl":70,"valueGlucoseTargetRangeHighInMgPerDl":140,"selectedTimeFormat":"24hr","selectedCarbType":"grams of carbs"},"timestamp":"<NOW_LOCAL_MS>"},"header":{"device":{"hardwareDescriptor":"GlucoBridge-spike","hardwareName":"GlucoBridge","modelName":"com.freestylelibre3.app.de","osType":"Android","osVersion":"36","uniqueIdentifier":"<DeviceId>"}},"measurementLog":{"capabilities":[CAPS],"currentGlucoseEntries":[…],"foodEntries":[…],"genericEntries":[…],"insulinEntries":[…],"scheduledContinuousGlucoseEntries":[…],"unscheduledContinuousGlucoseEntries":[]}},"UserToken":"<token>","Domain":"Libreview","GatewayType":"FSLibreLink3.Android"}
```
`CAPS` = `"scheduledContinuousGlucose","unscheduledContinuousGlucose","currentGlucose","insulin","food","generic-com.abbottdiabetescare.informatics.exercise","generic-com.abbottdiabetescare.informatics.ondemandalarm.low","generic-com.abbottdiabetescare.informatics.ondemandalarm.high","generic-com.abbottdiabetescare.informatics.ondemandalarm.projectedlow","generic-com.abbottdiabetescare.informatics.ondemandalarm.projectedhigh","generic-com.abbottdiabetescare.informatics.customnote","generic-com.abbottdiabetescare.informatics.sensorstart","generic-com.abbottdiabetescare.informatics.sensorEnd","generic-com.abbottdiabetescare.informatics.isfGlucoseAlarm","generic-com.abbottdiabetescare.informatics.alarmSetting","generic-com.abbottdiabetescare.informatics.bleDisconnect","generic-com.abbottdiabetescare.informatics.bleReconnect","generic-com.abbottdiabetescare.informatics.error"`

Language, target range (70/140) and 24 h format copy what Juggluco sends for this account today, so uploads
don't change the account's settings. `hardwareDescriptor`/`hardwareName` mark everything as spike data.
The header (incl. `osVersion` 36 / `Android/16`) is constant: phone and Mac present themselves as one device.

**Time formats** (local = the device's current zone, offset `±hh:mm`):
- `UTC_MIN` = `2026-10-15T09:34:00Z` — treatment `factoryTimestamp`: UTC, seconds forced to `00`, no millis
- `LOCAL_MIN` = `2026-10-15T12:34:00+03:00` — treatment `timestamp`
- `UTC_MS` = `2026-10-15T09:34:56.000Z` — glucose `factoryTimestamp` (millis always `000`)
- `LOCAL_MS` = `2026-10-15T12:34:56.000+03:00` — glucose `timestamp`, `deviceSettings.timestamp`

**Treatment record numbers** — one record per *version* (deterministic, so both apps produce the same row):
`recordNumber(id, v) = (first 5 bytes of SHA-256("<id>:<v>") as a big-endian integer) << 8 | type`,
type `rapid`=1, `long`=3, `carbs`=4, `note`=16. Always positive, < 2^48.
Measured: **LibreView ignores a changed value sent under an existing record number** (first write wins), so a
row is uploaded as: a retraction `recordNumber(id, lv)` with `DEL` if `lv ≠ 0` and (`del` or `lv ≠ u`), then — unless
`del` — the current version `recordNumber(id, u)`. A row deleted before any upload sends nothing.
Known gap: if a device edits a row before it has learned the other device's upload of the previous version, that
version is never retracted (a real app would keep the set of uploaded versions, merged by union).

Entries sorted by `t`, then `id` (retraction before the current version). `DEL` = `"action":"deleted",` on a
retraction, else empty.
- insulin → `insulinEntries`:
  `{"insulinType":"RapidActing"|"LongActing","units":2.5,"extendedProperties":{"factoryTimestamp":"UTC_MIN",DEL"linkedGlucoseRecordNumber":"0"},"recordNumber":N,"timestamp":"LOCAL_MIN"}`
  (`units` always with one decimal: `2.0`, `14.0`)
- carbs → `foodEntries`:
  `{"foodType":"Breakfast"|"Lunch"|"Dinner"|"Snack","gramsCarbs":60,"extendedProperties":{"factoryTimestamp":"UTC_MIN",DEL"linkedGlucoseRecordNumber":"0"},"recordNumber":N,"timestamp":"LOCAL_MIN"}`
  foodType (Juggluco's rule): if grams > 50, by local hour h: 4 ≤ h < 11 Breakfast, 11 ≤ h < 16 Lunch,
  16 ≤ h < 22 Dinner; otherwise Snack.
- note → `genericEntries`:
  `{"type":"com.abbottdiabetescare.informatics.customnote","extendedProperties":{"factoryTimestamp":"UTC_MIN","text":"<n>",DEL"linkedGlucoseRecordNumber":"0"},"recordNumber":N,"timestamp":"LOCAL_MIN"}`

**Glucose** (phone only — it has the sensor serial and life counts):
`histor = fold(c in last 9 chars of serial: acc = acc << 5 | unalf(c)) << 19` (64-bit wrapping),
`recordNumber = histor | lifeCount`. `unalf` = Juggluco's base-32 serial alphabet (`share/serial.hpp`).
- `currentGlucoseEntries` (the newest 1-minute reading, at most one):
  `{"extendedProperties":{"trendArrow":"Stable","isActionable":true,"isViewed":false,"factoryTimestamp":"UTC_MS","isFirstAfterTimeChange":false},"recordNumber":N,"timestamp":"LOCAL_MS","valueInMgPerDl":123.0}`
  trendArrow from rate r (mg/dL/min): r > 2 RisingQuickly, r > 1 Rising, r ≥ −1 Stable, r ≥ −2 Falling,
  else FallingQuickly; unknown → `Undetermined`.
- `scheduledContinuousGlucoseEntries` (5-minute history, ascending):
  `{"extendedProperties":{"canMerge":true,"isFirstAfterTimeChange":false,"factoryTimestamp":"UTC_MS"},"recordNumber":N,"timestamp":"LOCAL_MS","valueInMgPerDl":123.0}`
- once per sensor, first in `genericEntries`:
  `{"type":"com.abbottdiabetescare.informatics.sensorstart","extendedProperties":{"factoryTimestamp":"UTC_MS of sensor start","puckGen":0,"wearDuration":21600,"warmupTime":60,"productType":4},"recordNumber":histor,"timestamp":"LOCAL_MS now"}`

## 4. Spike hygiene

Every device appends each uploaded treatment row (`recordNumber`, `id`, `k`, `t`, deleted?) to a local ledger.
`lv.py cleanup` (and the apps' "delete all spike treatments") re-sends every ledger row as deleted.

## 5. Treatments over Nostr (fits investigation 03's [PROTOCOL.md](../nostr/PROTOCOL.md) and its v2 key design in investigation 06)

**Built** (2026-10-16, Sukrara for Android, both roles; Mac in parallel): writer → owner (investigation 07) and
owner → followers. Who publishes what, the follower filter and "echo = accepted" are in
[PROTOCOL-v2 "Treatments"](../nostr/PROTOCOL-v2.md). Differences from the proposal below: the owner publishes its
whole merged log with its **device key** `D` (a roster `devices` key, not a `writers` key); followers subscribe to
`authors = roster.devices ∪ roster.writers`, `since` now − 30 d (no cursor); the owner also republishes when a
follower is approved and weekly. Not built: the Mac's own `W` in `writers` (the Mac's entries reach followers through
the phone's log, signed by `D`).

Glucose stays as 03/07 define it. Treatments differ in one way that matters: **several devices create them**
(phone, Mac, later a parent's phone), so they can't all be "the uploader". Terms from 07's PROTOCOL v2 (draft,
2026-10-16 — names may still change): identity key `I` (root, in the password manager), rotatable device key(s),
read key `G_n` with **epochs** `n` (rotated when a follower is revoked, delivered by NIP-59 gift wrap), a **stable
stream id** `sid` (32 bytes, never rotates), and `H(x) = hex(sha256(x))[:32]`.

**Event**: kind `30078` (addressable, like 03's hourly chunks), one per treatment per author:
```
tags:    [["d", H("cgm2:tr:" + sid + ":" + id)],
          ["c", H("cgm2:treatments:" + sid)],                // opaque, same for every treatment → `#c` filter
          ["gk", hex(sha256(G_n.pub))[:8]],                  // which read-key epoch encrypted it (v2 convention)
          ["expiration", now + 30 days]]
content: nip44_encrypt(author.sec, G_n.pub, {"v":1, …§1 treatment JSON without "lv"})
created_at = now (publish time)
```
- **Tags derive from `sid`, not `G`**, so they survive read-key rotation; the `gk` tag tells the reader which `G_n`
  to decrypt with (no trial decryption). A revoked follower can still see *that* events exist (as the author key already
  reveals) but not read them.
- **`u` lives only in the content** and is the merge key; `created_at` is the publish time, because relays may
  reject old timestamps (NIP-11 `created_at_lower_limit`, rate limits). Replacement still works: per (author, d),
  the latest publish carries the latest state that author knows.
- **Signed by the device that made the change** (the phone with its device key, others with their own key `W`). Relays replace
  per *author*, so a Mac edit of a phone entry is a second event; followers apply the §1 merge (newest `u` wins),
  the same rule as for BLE.
- **Who may write = keys the identity authorized**: writer keys go in the `writers` array of v2's roster (kind 30078,
  `d = H("cgm2:roster:" + sid)`, signed by `I`) — not a list signed by the phone's key, which would silently drop
  every writer when the phone changes. The phone asks for the
  Mac's `W` to be added when they first pair over BLE. Followers ignore treatment events from keys not on the roster.
- **Follower**: `REQ {"authors":[roster writers], "kinds":[30078], "#c":[index], "since": cursor}` once on start and
  as a live subscription; decrypt with the epoch's `G`, check the author is on the roster, merge.
- **On a `G` rotation** the phone re-publishes the merged current state of every treatment still in the buffer,
  encrypted to the new epoch (signed by its device key, an authorized writer), paced per 03's rules.
- **30-day expiry** instead of 7: a few events per day cost nothing, and an entry the doctor will see shouldn't be lost
  because a laptop was closed for a week. Devices remain the database. Deletes are the same event with `"del":true`.

**LibreView stays with the LibreView devices** (phone + Mac, sharing one DeviceId). A Nostr-only follower never
uploads; entries it creates reach LibreView through the phone after the §1 grace period, like Mac entries do.

**Nightscout adapter (03's tier 1)** can serve `/api/v1/treatments.json` from the same log instead of `[]`:
rapid → `{"eventType":"Correction Bolus","insulin":a}`, carbs → `{"eventType":"Carb Correction","carbs":a}`,
note → `{"eventType":"Note","notes":n}`, with `_id`/`identifier` = `id`, `created_at` = ISO of `t`. Long-acting
has no single standard eventType in Nightscout (open question).

**Code seams**: GlucoBridge `TreatmentStore.onChange` → listener list (BLE + Nostr); all GATT notifications through
`BridgeService.enqueue()`.
