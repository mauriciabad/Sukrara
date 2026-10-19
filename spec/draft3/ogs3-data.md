# OGS3-D — Open Glucose Stream draft 3: data model

`draft 3` · 2026-10-19 · [index](README.md) · core: [ogs3-core.md](ogs3-core.md)

The payloads a stream carries. Each is the `content` of a signed record ([C§6](ogs3-core.md#c6-records)), the
same on every transport. The model was built from the requirements in [rationale.md](rationale.md) and checked
against Nightscout (entries, treatments, devicestatus; API v3), Tidepool's data model, the HL7 FHIR CGM
implementation guide 1.0.0, Open mHealth / IEEE 1752.1, Apple HealthKit, Android Health Connect, and the Bluetooth
CGM Service. The mappings are in D§11.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY and OPTIONAL are to be
interpreted as described in RFC 2119 and RFC 8174 when, and only when, they appear in all capitals.

## D§1 Conventions

- **Objects.** Every payload is a JSON object with a `type`: lowercase letters, digits and `_`, 1–32 characters.
  Receivers MUST ignore fields they don't know.
- **Integers** MUST be written without a fraction or exponent. Receivers SHOULD accept an integral value written as
  `5.0`, because some JSON libraries write whole doubles that way. **Numbers** accept both forms.
- **Optional** fields may be left out. Unless a field says otherwise, receivers treat a missing optional field and
  `null` the same way.
- **Open enumerations.** Fields marked *open* list the values this document defines. Producers MAY use other values
  of the same syntax (lowercase, digits, `_`). Receivers MUST keep a record with an unknown value, and SHOULD show it
  generically (an unknown alert kind uses its `prio` and `text`). Unknown values are never an error.
- **Text** fields are UTF-8, with the byte limits given. They are the only free-form data in the protocol; apps
  SHOULD tell users that followers read them.
- **No identifiers of people or devices.** There is no sensor serial number, transmitter id, account id, phone model
  or location anywhere. A sensor session is identified by a random `sess`. Producers MUST NOT add such fields, not
  even under unknown names.

## D§2 Units and time

### D§2.1 Glucose

Glucose is an **integer in mg/dL**, 1–1000, everywhere on the wire. There is no unit field and no mmol/L on the wire.
- Every common sensor measures and reports mg/dL natively: Libre, Dexcom, the Bluetooth CGM Service (mg/dL only).
  Integers are therefore lossless at the source.
- A value that arrives in mmol/L (from a bridge) is converted with D§2.2 and rounded to the nearest integer. The
  error is at most 0.5 mg/dL (0.03 mmol/L), below any meter's resolution.

### D§2.2 Conversion

```
mmol/L = mg/dL ÷ 18.01559        mg/dL = round(mmol/L × 18.01559)
```

18.01559 is the molar mass of glucose (180.1559 g/mol) divided by 10. Nightscout (`lib/constants.json`), Tidepool
(`MmolLToMgdLConversionFactor`) and xDrip+ use it. HealthKit's molar mass gives 18.0156 (the same to 5 digits).
AndroidAPS, Juggluco and Health Connect use 18.0. Values that pass through those can differ by 1 mg/dL at high
readings, which a receiver SHOULD tolerate when it deduplicates across systems.

Displays SHOULD show mmol/L with one decimal. The wearer's preferred unit travels in `settings` (D§7), so followers
can show the same unit as the wearer.

### D§2.3 Rate of change

A **rate** is in mg/dL per minute, positive when rising. In a `cgm` row it is an integer `rate × 100`. Elsewhere it
is a number. A source without a rate leaves it out. There is no arrow on the wire. D§4.4 derives arrows when a
target needs them.

### D§2.4 Time

- Every time is a **Unix timestamp in whole seconds** (UTC), except `rev` (D§9.1, D§5–D§8), which is in
  **milliseconds**, because it orders edits.
- `tz` (integer, OPTIONAL, −840…840): the **offset from UTC in minutes** at that time, east positive, as Nightscout
  `utcOffset`, Tidepool `timezoneOffset` and Health Connect `zoneOffset` carry it. Producers SHOULD set it on `cgm` and
  entries, because day charts, the AGP report and "breakfast" depend on local time.
- `zone` (in `settings`, OPTIONAL): the IANA zone name, for example `Europe/Madrid`.
- A publisher's clock is the reference for its own records. Receivers MUST NOT reject records for being up to
  5 minutes in the future, and SHOULD show a record more than that in the future as suspect.

## D§3 Record types

| type | lane | written by | identity (C§10.5) | changes | day (C§6.3) |
|---|---|---|---|---|---|
| `cgm` | g | device | (author, sess, start, len) | republished as it fills; corrections | `start` |
| `sensor` | r | device | `sess` | `rev` | publication |
| `device` | r | device | author | `rev` | publication |
| `settings` | r | device | stream | `rev` | publication |
| `alert` | r | device | `id` | `rev` | publication |
| `ack` | r | device, member with `ack` | (alert, author) | newest | publication |
| `bg`, `insulin`, `carbs`, `exercise`, `note` | r | device, member with `write` | (`by`, `id`) | `rev`, tombstones | `t` |
| `membership`, `epoch_end` | m | `I` | epoch | `seq` | publication |

## D§4 `cgm`: glucose series

```json
{"type":"cgm","sess":"s3ns0r-0001","start":1792085400,"len":900,"tz":120,
 "rows":[[0,118,-35],[60,117],[120,40,null,1],[180,121,12,32]]}
```

| key | type | meaning |
|---|---|---|
| `sess` | string, 8–64 of `[A-Za-z0-9_-]` | the sensor session (D§5) |
| `start` | integer | first second of the series; `start mod len = 0` |
| `len` | integer | series length in seconds. It MUST divide 86400, so a series never crosses midnight. RECOMMENDED: `900` for live blocks, `86400` for day archives. |
| `tz` | integer, optional | D§2.4 |
| `rows` | array, ≤ 2880 | readings, sorted by `dt`, strictly increasing |

### D§4.1 Rows

A row is `[dt, v]`, `[dt, v, r100]` or `[dt, v, r100, flags]`:
- `dt`: integer, `0 ≤ dt < len`; the reading's time is `start + dt`.
- `v`: integer mg/dL, 1–1000.
- `r100`: the rate × 100 as an integer, −3000…3000, or `null`.
- `flags`: an integer bitset, 0 if absent.

Receivers MUST NOT expect a fixed spacing: Libre 3 reads every minute, Dexcom every 5 minutes, and Libre 2 history
every 15.

### D§4.2 Flags

| bit | name | meaning | modelled on |
|---|---|---|---|
| 1 | `lo` | below the sensor's range: the true value is below `v` (`v` is the sensor's lower limit, e.g. 40) | CGMS "result lower than the device can process", Tidepool `bg/out-of-range`, Nightscout `sgv 39` |
| 2 | `hi` | above the sensor's range: the true value is above `v` (e.g. 400 or 500) | CGMS, Tidepool, Nightscout `401` |
| 4 | `warmup` | provisional, during warm-up | |
| 8 | `unreliable` | the source flagged it (noise, sensor error, temperature). Show it marked; never alert on it. | CGMS annunciation, Nightscout `noise` ≥ 3 |
| 16 | `calibrated` | adjusted by a user calibration (D§9.2) | |
| 32 | `backfilled` | recovered from sensor memory after a gap, not seen live | Tidepool `backfilled` |
| 64 | `interpolated` | estimated by the producer, not measured | |

Other bits are reserved. Receivers ignore bits they don't know. A reading with `lo` or `hi` MUST NOT be shown as an
exact number (show "< 40", "> 400"), and MUST NOT be averaged as if it were exact.

### D§4.3 Publishing a series

- **Live.** A publisher device republishes the current block (`len` 900) every time a reading arrives. Relays keep
  only the newest version, and push each version to subscribed members at once. That is the live channel: there are
  no separate "live" events. A device in a battery-saving mode MAY republish every few minutes instead.
- **Day archives.** For history requests, and to let 15-minute blocks expire early, a device MAY publish a day's
  readings as one series with `len` 86400 (about 25 KB for 1 440 rows).
- **Corrections.** A newer version of a series MAY change a value, add a rate, add rows that came late (`backfilled`)
  or set `unreliable`. Receivers apply C§10.5: by `(sess, t)`, the reading from the newest object wins.

### D§4.4 Arrows (informative)

For targets that want a trend arrow, use the Nightscout/Dexcom directions on the rate `r` in mg/dL/min:

| `r` | ≥ 3 | 2 ≤ r < 3 | 1 ≤ r < 2 | −1 < r < 1 | −2 < r ≤ −1 | −3 < r ≤ −2 | ≤ −3 | none |
|---|---|---|---|---|---|---|---|---|
| direction | `DoubleUp` | `SingleUp` | `FortyFiveUp` | `Flat` | `FortyFiveDown` | `SingleDown` | `DoubleDown` | `NONE` |

The Nightscout `delta` is `r × 5`. Tidepool's `trend` names map the same bands (`rapidRise` … `rapidFall`).

## D§5 `sensor`: sensor session

```json
{"type":"sensor","sess":"s3ns0r-0001","state":"active","start":1791828000,"warmup_end":1791831600,
 "end":1793124000,"model":"libre3","interval":60,"rev":1792086510000}
```

| key | type | meaning |
|---|---|---|
| `sess` | string | random, made when the session starts. It is never derived from the serial. |
| `state` | open: `warmup`, `active`, `ended` | |
| `start` | integer, optional | session start |
| `warmup_end` | integer, optional | when readings become usable |
| `end` | integer, optional | expected end (start + wear length) |
| `ended` | integer, optional | when it actually ended |
| `reason` | open, optional: `expired`, `removed`, `failed`, `replaced`, `unknown` | why it ended |
| `model` | string ≤ 32, optional | a coarse family (`libre3`, `dexcom_g7`), never a serial |
| `interval` | integer, optional | seconds between readings |
| `rev` | integer ms | version |

A device publishes it when the session changes, and at least once a day while it runs, so members holding only
recent days still know the session. Several sessions can be active at once (overlap during a sensor change, or two
sensors worn for comparison). Each has its own `sess`, and members show the one whose readings are newest unless the
`settings` say otherwise.

## D§6 `device`: publisher status

```json
{"type":"device","label":"Phone","battery":81,"charging":false,"link":"ok","last":1792086500,"rev":1792086510000}
```

`label` (≤ 40 bytes), `battery` (0–100), `charging` (boolean), `link` (open: `ok`, `lost` = no data from the
sensor, `off` = Bluetooth or the app is off), `last` (the last contact with the sensor), `rev`. All but `rev` are
optional. A device SHOULD refresh it every 15 minutes while it publishes. This helps followers tell "no data"
apart from "phone dead", and the steady rhythm also masks the timing of other records in the same lane (S§3).

## D§7 `settings`

```json
{"type":"settings","unit":"mmol/L","target":[70,180],"zone":"Europe/Madrid","rev":1792086520000}
```

- `unit`: `mg/dL` or `mmol/L`, the wearer's display preference. Members SHOULD default to it.
- `target`: `[low, high]` mg/dL, the wearer's target range, 40 ≤ low < high ≤ 400. The international consensus range
  is 70–180.
- `zone`: D§2.4.
- `rev`: the version.

## D§8 Alerts

### D§8.1 `alert`

The publisher tells members about each alert it raises, repeats and clears, so they can alert at the same moment
with the same meaning. The rules that raise alerts are the publisher app's own. This section only defines what goes
on the wire.

```json
{"type":"alert","id":"a1b2c3d4e5f60718","kind":"high","prio":"time_sensitive","state":"active",
 "start":1792086900,"last":1792086900,"n":1,"v":192,"r":0.4,"th":180,"rev":1792086900000}
```

| key | type | meaning |
|---|---|---|
| `id` | 16 hex characters | random per episode. The same `id` is updated through repeats and the clear. |
| `kind` | open: `very_low`, `low`, `high`, `very_high`, `falling_fast`, `rising_fast`, `predicted_low`, `no_data`, `sensor_ending`, `sensor_error`, `signal_loss` | |
| `prio` | open: `critical`, `time_sensitive`, `normal` | how to present it. Unknown values → `normal`. Tidepool uses the same three levels. |
| `state` | `active` or `cleared` | |
| `start`, `last` | integer | first fire, and the latest repeat |
| `n` | integer ≥ 1 | repeat count (1 = first) |
| `end` | integer, optional | when it cleared |
| `v`, `r` | optional | the reading behind it |
| `th` | number, optional | the threshold that matched: mg/dL for levels, mg/dL/min for rates, minutes for `no_data` |
| `text` | string ≤ 200 bytes, optional | a fallback text for apps that don't know `kind` |
| `rev` | integer ms | version |

### D§8.2 Receiving alerts

- A member notifies its user when it accepts a version with `state: active` whose `last` is at most **10 minutes**
  old and whose `(id, n)` it hasn't notified before. Older active alerts are shown, without a sound.
- An alert isn't a delivery guarantee. Relays can be slow, phones can be offline. Apps SHOULD make "no data for N
  minutes" an alert of their own (S§9).
- Producers SHOULD NOT send alerts the wearer muted. Whether a follower alerts at all is the follower's choice.

### D§8.3 `ack`

```json
{"type":"ack","alert":"a1b2c3d4e5f60718","t":1792086960,"note":"on my way"}
```

A member with the `ack` scope, or a device, acknowledges an alert, so other carers know someone is on it. `note` is
at most 200 bytes. Publishers MAY stop repeating an alert once it is acknowledged; that is the publisher's rule. No
consumer product offers this coordination between carers today. Tidepool's `alert` has an `acknowledgedTime`, but
only for the device's own user.

## D§9 Entries

Insulin, carbs, fingersticks, exercise and notes, written by the wearer's devices or by members with `write`.

### D§9.1 Common fields

| key | type | meaning |
|---|---|---|
| `id` | lowercase UUID v4 | made once by the creator; never changes |
| `t` | integer | when it happened |
| `rev` | integer ms | version: each change increases it |
| `del` | boolean, optional | tombstone. A deleted entry stays published (until it expires) so the delete spreads. |
| `by` | hex key, optional | the creator. Absent means the author of the record. A device editing a member's entry sets `by` to that member's key. |
| `tz` | optional | D§2.4 |
| `note` | string ≤ 200 bytes, optional | |

An entry is identified by `(by, id)`. Only its creator and the wearer's devices may change it (C§10.5). A member
can't touch the wearer's entries or another member's: `by` would have to be its own key, and a different `by` is a
different entry.

### D§9.2 `bg`: fingerstick or calibration

`v` (integer mg/dL), `cal` (boolean: used to calibrate the sensor), `sess` (the calibrated session, when `cal`),
`src` (open: `meter`, `manual`). This covers Nightscout `mbg` and `cal`, and Tidepool `smbg` and
`deviceEvent/calibration`.

### D§9.3 `insulin`

`u` (number, units, 0–250, up to 3 decimals), `kind` (open: `rapid`, `long`, `intermediate`, `premixed`, `other`),
`how` (open, optional: `pen`, `syringe`, `pump`, `inhaled`). Pump basal programmes, temporary basals and automated
delivery are out of scope for draft 3 (rationale R10).

### D§9.4 `carbs`

`g` (number, grams, 0–1000), `meal` (open, optional: `breakfast`, `lunch`, `dinner`, `snack`, the values of Health
Connect `mealType`), `fat` and `protein` (optional, grams).

### D§9.5 `exercise`

`dur` (integer minutes, 1–1440), `level` (open, optional: `light`, `moderate`, `vigorous`), `what` (string ≤ 32
bytes, optional, for example `walk`).

### D§9.6 `note`

`text` (≤ 500 bytes), `tag` (open, optional: `illness`, `stress`, `alcohol`, `menstruation`, `travel`, `other`).

## D§10 Profiles

A display name and a generated picture per participant, so lists show faces instead of keys. Profiles exist **only
inside encrypted payloads**: the invite (the wearer's), the join (the member's) and the membership (the wearer's).
They are never published as Nostr kind 0 or anywhere else in public.

```json
{"name":"Alex","avatar":{"v":1,"c":0,"s":"cube","a":45,"n":1497950029}}
```

- `name`: at most 40 characters. Self-asserted: it helps people recognise each other and authenticates nothing.
- `avatar`: OPTIONAL parameters of a generated picture, never an image or a URL (fetching a URL would tell its server
  who looks at the stream). `v` names the renderer. A receiver that doesn't know `v` MUST keep and forward the object
  unchanged, and draws a fallback (the name's first letter on a neutral background). Receivers MUST ignore an avatar
  over 512 bytes of JSON. Renderer 1 is described in draft 1's ogs-data Annex A. A new renderer MUST NOT include any
  vendor string in its derivations.

## D§11 Bridges (informative)

A member app can feed other systems. Each bridge leaves the encrypted channel, so the app MUST ask the user first,
and SHOULD explain what the other system will store. The reference implements the mappings below
(`ogs/d3/bridges.py`), and the vectors pin one example of each.

### D§11.1 Readings

| Target | Mapping of a row `(sess, t, v, r, flags)` |
|---|---|
| **Nightscout** entries | `{"type":"sgv","sgv":v,"date":t·1000,"dateString":ISO(t,tz),"utcOffset":tz,"direction":D§4.4,"delta":r·5,"noise":1 (4 if unreliable),"identifier":"ogs3:<sess>:<t>"}`. `lo` → `sgv` 39, `hi` → `sgv` 401: Nightscout's in-band "LOW"/"HIGH". |
| **HL7 FHIR CGM IG 1.0.0** | Observation, profile `cgm-sensor-reading-mass-per-volume`, LOINC **99504-3** (glucose, mass/volume, interstitial fluid), `valueQuantity` UCUM `mg/dL`, `effectiveDateTime` with the offset. `lo`/`hi` → `valueQuantity.comparator` `<`/`>`. The IG has no trend, quality or calibration element. |
| **Health Connect** | `BloodGlucoseRecord`: `time`, `zoneOffset`, `level` mg/dL, `specimenSource` `INTERSTITIAL_FLUID`, `metadata.clientRecordId` `ogs3:<sess>:<t>`, `clientRecordVersion`, `recordingMethod` `AUTOMATICALLY_RECORDED`. **Skip** `lo`/`hi` rows: the record can't say "below range". |
| **HealthKit** | `HKQuantityTypeIdentifierBloodGlucose` in mg/dL, `HKMetadataKeySyncIdentifier` `ogs3:<sess>:<t>`, `HKMetadataKeySyncVersion`, `HKMetadataKeyTimeZone` from `settings.zone` |
| **Tidepool** | `cbg` with `units` `mg/dL`, `trendRate`/`trendRateUnits` `mg/dL/minute`, `sampleInterval`, `backfilled`, `timezoneOffset`; `lo`/`hi` → annotation `{"code":"bg/out-of-range","value":"low"/"high","threshold":v}` |
| **Open mHealth / IEEE 1752.1** | `blood-glucose` `{value, unit "mg/dL"}`, `specimen_source` `interstitial fluid`, `effective_time_frame` |
| **Bluetooth CGMS** | the optional read-only bridge of draft 1 (ogs-ble Annex B) still applies: `v` → SFLOAT, `lo`/`hi` → the "result lower/higher than the device can process" annunciation bits. |

### D§11.2 Entries

| OGS | Nightscout `eventType` | Tidepool | HealthKit | Health Connect |
|---|---|---|---|---|
| `insulin` `rapid` | `Correction Bolus` (`insulin`) | `bolus`/`normal` or `insulin` | `insulinDelivery`, reason bolus | none: Health Connect has no insulin record |
| `insulin` `long` | `Note` with `insulin` and "long-acting" (Nightscout has no long-acting type; xDrip+ uses `insulinInjections`) | `insulin` (long-acting formulation) | `insulinDelivery`, reason basal | none |
| `carbs` | `Carb Correction` (`carbs`) | `food` (`nutrition.carbohydrate`) | `dietaryCarbohydrates` | `NutritionRecord.totalCarbohydrate` |
| `bg` | `BG Check` (`glucose`, `glucoseType` Finger) | `smbg` / `deviceEvent` calibration | `bloodGlucose` (meal time if known) | `BloodGlucoseRecord`, `CAPILLARY_BLOOD` |
| `exercise` | `Exercise` (`duration`) | `physicalActivity` | workout | `ExerciseSessionRecord` |
| `note` | `Note` (`notes`) | annotation | — | — |
| `sensor` start/end | `Sensor Start` / `Sensor Stop` | `cgmSettings` / `deviceEvent` | — | — |
| `device` | `devicestatus` `uploader.battery` | — | — | — |

The entry `id` maps to Nightscout `identifier`, HealthKit's sync identifier and Health Connect's `clientRecordId`,
and `rev` to the sync version / `clientRecordVersion`.

### D§11.3 Summaries for clinicians

OGS carries readings, not reports. A member app with a clinician's time-limited grant can compute the consensus
metrics and send them as the FHIR CGM IG summary observations:
- time in ranges (LOINC 97510-2 for 70–180, 104642-4, 104641-6, 104640-8, 104639-0 for the other bands, panel
  106793-3);
- mean glucose (97507-8);
- GMI (97506-0);
- coefficient of variation (104638-2);
- days of wear (104636-6) and sensor active % (104637-4);
- bundled with the readings in a `CGMDataSubmissionBundle`.

Readings with `lo`/`hi` count in the lowest/highest band. `unreliable` and `interpolated` readings SHOULD be
excluded.

## D§12 Extensibility

- New optional fields can be added to any type without a version change. Receivers ignore unknown fields (D§1).
- New types: a receiver keeps a record of an unknown type (C§12), and an app MAY show its `text` field if there is
  one. A type whose meaning changes gets a new name (for example `cgm2`). The type registry is this document. New
  names are registered by a pull request to the spec repository. Experimental types SHOULD start with `x_`.
- New values of open enumerations need no change at all (D§1).
- Planned candidates, not defined in draft 3: pump and automated-delivery status (`basal`, `loop` with IOB, COB and
  predictions), `ketones`, summary records, and a `heart_rate` or `activity` context.
