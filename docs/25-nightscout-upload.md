# 25 — Nightscout upload: glucose and treatments to the user's own Nightscout

*2026-10-19 · investigation report · standalone PoC `spikes/nightscout-upload` (Kotlin JVM uploader library + CLI),
verified against a local Nightscout 15.0.8 + MongoDB 7 in Docker, synthetic data only · source read from the
local checkouts in `third_party/`: cgm-remote-monitor `92d0834` (15.0.8), xDrip+ `8532095`, Juggluco `a983788a`,
AndroidAPS `aa16db6419`, LoopFollow `4a74b78`, nightguard, Trio `0b29cd5`*

## Goal

[22](22-ecosystem-outputs.md) ranked **Nightscout upload** as Sukrara's biggest gap: iPhone followers (LoopFollow,
nightguard, Trio's "Nightscout as CGM"), Nightscout reports and Home Assistant away from home all read a Nightscout
*site*, and Sukrara only serves a Nightscout-compatible API on the phone itself ([03](03-nostr.md)). Questions:

1. What exactly should Sukrara upload: entries, treatments, devicestatus, sensor changes? Two-way or not? What may a
   follower upload?
2. How do xDrip+, Juggluco and AAPS do it (API v1 vs v3, auth, batching, backfill, dedupe)?
3. Can a small uploader with a queue, retry/backoff, backfill and dedupe do it right, and do the followers really read
   what it writes?
4. Where does the secret live, and what leaves the phone?

## Short answer

- **It works.** The PoC uploader filled a real local Nightscout and every reader we could check sees it correctly:
  **24/24** request-and-rule checks for LoopFollow, nightguard, Trio and Home Assistant; **Trio's and LoopFollow's own
  Swift decoders**, compiled from their source, decode every reading; **stock Home Assistant 2026.10.1**'s Nightscout
  integration shows `sensor.blood_sugar` with delta and direction; Nightscout's own web client shows the value, delta,
  arrow, sensor age (3d0h), uploader battery and IOB.
- **Robust by construction**: 2 851 readings (48 h at 1/min) backfilled in 6 requests / ~0.6 s; a 30-min hole filled
  when the readings arrived late; Nightscout down for ~2.5 min *and* 20 entries deleted from its database → healed by
  itself on the next run, **0 gaps, 0 duplicates**. 19 unit tests cover the queue against an in-memory Nightscout.
- **API v1 for entries, both APIs supported.** v1 takes arrays (500 per request), v3 has no bulk insert (one request per
  reading, `limit` ≤ 1000 on reads). Both are implemented; the PoC picks per site. **Auth: both** — the API secret
  (sent as `api-secret: sha1`, v1 only) and an **access token** (exchanged for a JWT, v1 + v3). Recommend the token: a
  role can be limited to exactly what the uploader needs (verified: it can't read profiles) and revoked alone.
- **Upload only, no reading back.** Sukrara's shared treatment log is co-edited over Nostr with verified authors and
  last-writer-wins; Nightscout has neither. Nightscout is an *output*. A later read-only overlay of other apps'
  treatments (AAPS boluses) is possible, never merged into the log (§3.6).
- **Only the wearer uploads.** A follower's copy is someone else's data: it never goes up unless the user explicitly
  picks "relay this person", and then under a different `device`. If another uploader (Juggluco, xDrip+) is already
  writing the site, Sukrara **pauses and asks** instead of producing a zig-zag for every follower.
- Five things learnt the hard way, all handled: long-acting insulin must **not** carry an `insulin` field (Nightscout
  counts every `insulin` as a rapid bolus in IOB); v3 refuses to change a stored `date`/`utcOffset` (a moved treatment
  must be deleted and created again); a plain v3 DELETE is a **soft** delete that v1 readers still show; Trio throws
  away the whole batch on one unknown `direction` string; sensor age needs **both** `Sensor Start` (LoopFollow) and
  `Sensor Change` (nightguard).

## 1. How the others upload, from source

| | xDrip+ | Juggluco | AAPS (NSClient v3) |
|---|---|---|---|
| API | v1 (`NightscoutUploader.java:130-162`, Retrofit) | v1 or v3, a switch (`uploader.cpp:66-71`) | v3 only (`NightscoutRemoteService.kt:31-103`) |
| Auth | `api-secret: sha1(secret)` (`:407`) | v1: `api-secret: sha1`; v3: `GET /api/v2/authorization/request/<token>` → `Authorization: Bearer <jwt>`, refreshed at `exp` (`NightPost.java:190-215`) | JWT from the access token, interceptor refreshes on 401/403 (`NSAuthInterceptor.kt:16-55`) |
| Entries | queue rows (`UploaderQueue`, a bit per destination), up to 300 per POST (`UploaderQueue.java:246`) | per-sensor cursor `nightiter`; v1: **one array of up to 5 000** (`uploader.cpp` `uploadCGM`), v3: one POST per reading | one POST per reading + `slowDown()` (`NSClientV3Plugin.kt:514-556`) |
| Backfill | queue holds what failed; a "backfill required" notification on multi-site inconsistency (`:537`) | from the cursor, at most **30 days** back (`nighttimeback`) | whatever the local DB hasn't marked with a Nightscout id |
| Retry | queue stays; next upload cycle | 15 min after a failure, 60 min without network | WorkManager; `ignoreNightscoutV3Errors` |
| Dedupe | server (`sysTime`+`type`), `uuid` on treatments | server; deterministic treatment identifiers | `identifier` from the server's answer (`isDeduplication`) |
| Treatments | `PUT` (upsert) with `uuid`; also **downloads** treatments (two-way) | v1 `PUT`, v3 POST; deletes by identifier; `eventType:"<none>"`, `insulin`+`insulinType` | full two-way sync (it is a loop: needs pump data from others) |
| devicestatus | phone/bridge battery `{device, uploader:{battery}}` on change (`:1100-1180`) | — | loop status, pump, uploader battery |

Entry shapes are the same everywhere: `type:"sgv"`, integer `sgv`, `date` ms, `dateString`, `direction`, `delta`,
`noise`, `device` (xDrip+ adds `filtered`/`unfiltered`/`rssi`/`sysTime`).

**The server** (cgm-remote-monitor 15.0.8):
- v1 entries upsert on `sysTime`+`type` (`lib/server/entries.js:238-256`). The server derives `utcOffset` from
  `dateString`'s offset and then rewrites `dateString` to UTC (`:122-128`).
- v1 treatments upsert on `identifier` first (`lib/server/treatments.js:356-381`). The fallback is
  `created_at`+`eventType`, which was the only rule before 15.x (from memory, not verified here: our clone is shallow).
- v3 `POST` dedupes on `identifier`; without one, the server computes `uuid.v5(device_date[_eventType], "NightscoutRocks!")`
  (`lib/api3/shared/operationTools.js:97-126`). On a match it replaces the stored document, but it refuses to change
  immutable fields.
- v3 requires `app`, a numeric `date` and `utcOffset` (`operationTools.js:56-86`). It accepts only a Bearer JWT
  (`lib/api3/security.js`), and the JWT can only be obtained from an **access token**, never from the API secret
  (`lib/authorization/endpoints.js:21`).
- The API secret is checked as SHA-1 or SHA-512 (`lib/server/enclave.js:50`).

## 2. What the followers read

From their source, then checked by running their requests (and two real decoders) against the filled server.

| Reader | Request | Must-haves (what breaks it) |
|---|---|---|
| **LoopFollow** | `entries.json?count&find[date][$gte]&find[type][$ne]=cal`, `token=` (`BGData.swift:70-76`) | numeric `sgv` + numeric `date` in ms on *every* entry: one bad entry fails the whole fetch (`ShareClientExtension.swift:7-37`). Treatments by `created_at`: `Carb Correction`→`carbs`, `Correction Bolus`→`insulin`, `Note`→`notes`. SAGE = `Sensor Start` only. Battery = `devicestatus.uploader.battery` |
| **nightguard** | tries **v3** first (`/api/v3/entries`, Bearer JWT), falls back to v1 (`NightscoutService.swift:419, 515, 910-1054`) | lenient parser; direction outside the arrow set shows `-`. SAGE = **`Sensor Change`** only (`:815-820`); battery via `/api/v2/properties` `upbat` |
| **Trio** "Nightscout as CGM" | `entries/sgv.json?count=1600&find[dateString][$gte]=<ISO>`, `api-secret` only (`NightscoutAPI.swift:68-104`) | `dateString` **required** and range-filtered **as a string** (so it must be stored as UTC `…Z`); `direction` must be in Trio's enum, any other string (e.g. `"None"`) throws and **the whole batch is dropped** (`BloodGlucose.swift:5-16, 78-121`); `noise` an Int |
| **Home Assistant** (`nightscout`, py-nightscout) | `status.json` + `entries/sgv.json`, `api-secret` | numeric `delta` on the newest entry (py-nightscout crashes on null, [03](03-nostr.md)) |
| **Nightscout web** | socket.io + `/api/v2/properties` | `sgv`, `date`; computes its own 5-min delta from 1-min data; SAGE from either event; IOB from **any** treatment with `insulin` (`lib/plugins/iob.js:187`) |

A suspected LoopFollow crash didn't happen: `SAge.swift:41-46` parses `created_at` with an `ISO8601DateFormatter` and
force-unwraps the result. Run on the server's `2026-10-15T21:46:06.209Z`, that formatter parses it
(`evidence/follower-decoders.txt`).

## 3. What Sukrara should upload (spec)

### 3.1 Entries (every reading of the wearer's own sensor, 1/min)

| Field | Value | Why |
|---|---|---|
| `type` | `"sgv"` | everyone filters on it |
| `sgv` | Int mg/dL | LoopFollow/Trio decode numbers; Int avoids `155.0` |
| `date` | Int ms | LoopFollow requires it, v3 requires it |
| `dateString` | v1: local time **with offset** (`2026-10-18T17:46:00.000-04:00`); v3: UTC `…Z` | v1 turns the offset into `utcOffset` and stores UTC; v3 takes `utcOffset` as given. The stored document is identical whichever API wrote it (mixing them otherwise hits v3's "utcOffset cannot be modified") |
| `utcOffset` | the wearer's offset in minutes | reports in local time; required by v3 |
| `direction` | Nightscout's enum from the rate (Sukrara's `XdripServer.direction` thresholds); **`NONE`** when unknown, never `"None"` | Trio drops the batch on unknown strings |
| `delta` | 5-min delta, 3 decimals (rate × 5) | Home Assistant shows it; py-nightscout needs it |
| `noise` | `1` | Int for Trio; Libre 3 gives no noise level |
| `device` | `"Sukrara"` (relay: `"Sukrara relay"`) | shown in Nightscout; part of the v3 identifier; conflict detection keys on it |
| `app` | `"Sukrara"` | required by v3 |
| `identifier` | `uuid.v5("Sukrara_<date>", "NightscoutRocks!")`, the same as the server's own calculation | v3 dedupe with no server-log warnings; equal across v1/v3 |

Not uploaded: raw/filtered values (Libre 3 has none), calibrations, `mbg`, the sensor serial on entries.

### 3.2 Treatments

| Sukrara kind | `eventType` | Fields |
|---|---|---|
| rapid insulin | `Correction Bolus` | `insulin`, `insulinType:"rapid-acting"` |
| long-acting insulin | `Note` | `notes:"Long-acting insulin 14 U"`, `insulinType:"long-acting"`, **no `insulin`** (it would count as a bolus in IOB everywhere) |
| carbs | `Carb Correction` | `carbs` |
| note | `Note` | `notes` |

Every treatment also carries:
- `identifier` = Sukrara's treatment UUID, stable across edits.
- `created_at` (UTC with ms), `date`, `utcOffset`.
- `enteredBy:"Sukrara"` (not on Trio's carb-exclusion list), `app`, `device`.
- A small `sukrara:{k,a,u}` object, so the document round-trips without loss.

Edits, moves and deletes follow the shared log. Every change of a treatment (`u` grows) queues its id. How each API
handles the upload:
- **v1** upserts on the identifier. When `created_at` changed, the uploader first deletes the old document by `_id`.
  That makes moves safe on 14.x servers too (unit test `treatmentMoveOnNightscout14DoesNotDuplicate`).
- **v3** POST replaces in place. When the server answers *"Field date cannot be modified by the client"*, the
  uploader deletes and re-creates the document (verified on the real server: carbs 30 → 35 and moved 10 → 20 min ago
  leave 1 document).
- **Deletes are permanent**: v1 does `DELETE /treatments/<_id>`, v3 does `DELETE …?permanent=true`. A plain v3 DELETE
  only sets `isValid:false`, and v1 readers kept showing the carbs (verified: 1 document still visible).

**Sensor start**: `Sensor Change` + `Sensor Start` with the same time and their own identifiers
(`uuid.v5("sukrara-sensor_<serial>_<type>")`), notes `Libre 3 <serial>`. Without both, LoopFollow and nightguard
disagree on the sensor age.

### 3.3 devicestatus

`{device:"Sukrara", created_at, uploader:{battery, isCharging}}`, sent when the level changes, at most every 30 min
otherwise (xDrip+ sends on change only). It feeds Nightscout's `upbat` pill, LoopFollow's battery and nightguard. No
pump, loop or IOB fields: Sukrara isn't a loop, and LoopFollow's "not looping" alarm only fires once a `loop` was
ever seen, so a CGM-only site gets no false alarm.

### 3.4 What leaves the phone

Only what's listed above, and only to the URL the user typed:
- glucose values with their time and arrow
- the shared treatment log (amounts, notes, times)
- sensor start time and serial
- the phone's battery level

Nothing about followers: no names, no Nostr keys, no LibreView data, and the phone's model and location are never
sent. The `device` field is the constant `"Sukrara"`.

### 3.5 Roles

| Role | Uploads | Why |
|---|---|---|
| **Wearer** (owns the sensor) | entries, treatments, sensor events, battery | it's their data and their Nightscout |
| **Follower** | **nothing by default** | a follower's copy is someone else's data. Uploading it under the follower's own sensor identity would make a second, laggier "sensor" on that person's site, or put another person's glucose into the follower's own site |
| Follower with an explicit "Relay *Alice* to this Nightscout" | Alice's readings only, `device:"Sukrara relay"`, no treatments (Alice's phone owns the log) | for a parent whose child's phone can't reach the internet. One person per site, chosen by name, off by default |

The guard is in the library, not the UI: `Origin.FOLLOWED` readings are never queued unless they belong to
`NsConfig.relayPerson` (tested, and on the real server: 10 followed readings, 0 uploaded).

**Conflict guard**: on the first run, after failures and every 6 h, the uploader diffs the last window against the
server. If sgv entries from another `device` arrived in the last 30 min, the entries pause
(`Health.CONFLICT: another uploader is active: xDrip-LibreReceiver`) until the user confirms. Treatments still go up.
Two uploaders writing the same minutes look like a zig-zag to every follower. The Juggluco migration case is the
common one: Juggluco's uploader must be switched off first.

### 3.6 Two-way? No: upload only

Reading treatments back from Nightscout into Sukrara's log is **not** recommended:
- **Identity**: Nostr events are signed by a known person (wearer or an allowed writer). A Nightscout treatment is
  signed by nobody: anyone with the site's careportal role or API secret can write one.
- **Versioning**: the log is last-writer-wins on `u`. Nightscout has `srvModified` only on v3 and no author. Merging
  them invites echo loops (upload → download → "newer" → upload) and silent overwrites.
- **Semantics**: AAPS/Loop treatments (SMB, temp basals, profile switches) don't fit Sukrara's four kinds, and a pump
  bolus written into Sukrara's log would then be uploaded to LibreView as a manual dose.

If wanted later: a **read-only overlay**. Fetch treatments whose `enteredBy` ≠ `Sukrara` (and `app` ≠ `Sukrara`), show
them on the graph with a "from Nightscout" mark, and never write them to the log, Nostr or LibreView.

## 4. The PoC

```
spikes/nightscout-upload/
  core/   the library Sukrara would embed (plain JVM: org.json + HttpURLConnection, both in Android)
    Model.kt      Reading (with Origin), Treatment, SensorStart, UploaderBattery
    NsConfig.kt   URL rules (https, http only for localhost), NsAuth.ApiSecret | AccessToken, NsApi.V1 | V3, limits
    NsDocs.kt     the documents of §3, the server-equal v5 identifier, sha1
    NsClient.kt   auth (sha1 header / JWT exchange + refresh), v1 + v3 calls, outcome classification
    Uploader.kt   the queue: enqueue hooks, sync(), backoff, diff-based backfill, roles, conflict guard, persistence
    Http.kt       the one network seam (fake in tests)
  cli/    seed/tick/late/follow/treat/sync/check over a local.json "phone store" (synthetic only)
  followers/   Trio's + LoopFollow's real Swift decoders compiled from third_party/ against the server
  verify/      nightscout.sh (Docker), all.sh, outage.sh, v3-and-deletes.sh, roles-and-conflict.sh, ha_check.py
  evidence/    the outputs quoted here
```

**Queue.** The app calls the hooks as things happen: `onReadings(list)` (new readings, and late ones from the sensor's
own backfill), `onTreatmentChanged(t)`, `onSensorStart(s)`, `onBattery(b)`. The queue stores **timestamps and ids,
not documents**: the readings stay in the app's store and documents are built at send time. So the queue is small
and an edit never sends a stale copy. It is persisted after every change, and a new process carries on (tested).

**Sync** (`sync()` from a worker; Sukrara: after each reading and from a periodic job), in this order:
1. **Diff** against the server: on the first run (7 days), after any failure (outage + 24 h, capped at 7 days), and
   every 6 h (24 h). Every own reading the server lacks is queued. One mechanism heals an outage, a wiped or restored
   database, and readings that reached the phone late. v3 pages in `limit=1000` steps with `fields=date,device`.
2. Drop entries older than the backfill horizon.
3. Entries oldest first: **v1 batches of 500**; v3 one per request, at most 240 per run.
4. Treatments (upsert / move / delete), then sensor events, then battery.

**Errors.**
- Network errors, 408, 429 and 5xx back off: 30 s, doubling, capped at 15 min, with ±20 % jitter. A larger
  `Retry-After` wins.
- 401/403 stop everything until the user fixes the secret or token (`resetAuth()`). No hammering with a wrong secret.
- Any other 4xx drops that document and reports it, so one bad document can't block the queue.
- **Dedupe** happens twice: locally (a timestamp is queued once) and on the server (v1 `sysTime`+`type`, v3
  `identifier`). A retry after a lost response never duplicates (tested with "stored, but answered 500").

**v1 vs v3.** Both are implemented. **Recommend v1 + access token as the default**:
- v1 is the only API with bulk insert. 2 851 entries took 6 requests on v1; v3 would need 2 851 requests. Juggluco
  uses v1 arrays of up to 5 000 for the same reason.
- v1 is what every reader in §2 uses.
- On 15.x it dedupes treatments by identifier as well as v3 does.

v3 stays as an option, for sites that disable v1, and is the base for a future read-only overlay (its `history`
endpoint).

**Auth.** Recommend an **access token** with a dedicated role:

```
sukrara-uploader: api:entries:create, api:entries:update, api:entries:read,
                  api:treatments:create, api:treatments:update, api:treatments:read, api:treatments:delete,
                  api:devicestatus:create, api:devicestatus:update, api:status:read
```

It works on v1 and v3 and can be revoked alone. Verified: it may not read `profile.json` (401). Accept the API secret
too, because most users only have that. With the secret, the uploader is limited to v1 and sends only its SHA-1 in
the `api-secret` header, never the plain text (the server accepts SHA-1 or SHA-512 (`enclave.js:50`). SHA-512 would
be marginally better on the wire, but Trio and older servers use SHA-1).

### Verification (evidence in `spikes/nightscout-upload/evidence/`)

Local Nightscout 15.0.8 (the official image) + MongoDB 7 in Docker, bound to 127.0.0.1. `AUTH_DEFAULT_ROLES=denied`,
so every reader had to authenticate. The phone's zone was pinned to America/New_York so `utcOffset` is visibly
non-zero. `verify/all.sh` runs everything below.

| Check | Result | File |
|---|---|---|
| Seed 48 h at 1/min with a 30-min hole, 3 treatments, sensor start; sync v1 + API secret | 2 851 entries in 6 POSTs, ~0.6 s; treatments 3, sensor events 2, battery 1 | `upload-v1.txt` |
| The hole's readings arrive late; sync | 30 queued, sent; **48 h: 0 gaps > 1 min, 0 duplicates** | `upload-v1.txt` |
| Nightscout stopped ~2.5 min while readings keep coming + 20 server entries deleted from MongoDB | backs off (30 s, 60 s…); after restart one run sends the new ones and **re-queues the 20 lost** (`reconcile 24 h: … queued 20 missing`); 6 h: 0 gaps, 0 duplicates | `outage-v1.txt` |
| Same site, API v3 + least-privilege token | diff finds nothing missing (no cross-API duplicates); edit + move → 1 document with the new amount/time; plain v3 delete leaves it visible to v1, ours removes it; v1 move/delete too; uploader token can't read profiles | `v3-and-deletes.txt` |
| LoopFollow / nightguard / Trio / HA requests + rules | **24/24** | `follower-check.txt` |
| Trio `BloodGlucose` + LoopFollow `ShareGlucoseData` decoders (their source, compiled with `swiftc`), LoopFollow's SAGE date parser | all decode every reading; SAGE parses | `follower-decoders.txt` |
| Stock **Home Assistant 2026.10.1**, built-in Nightscout integration via its config flow (URL + API key) | `sensor.blood_sugar = 80 mg/dL`, `delta -0.65`, `direction Flat`, `device Sukrara` | `home-assistant.txt` |
| Nightscout's web client | tab title rendered by the client (`165 +5 →`, earlier run); pills on the final run: 80 mg/dL, delta −1, Flat, SAGE 3d0h (both events), battery 64 %, IOB 0.77 U (the 4.5 U bolus; the 14 U long-acting adds nothing) | `nightscout-web.txt` |
| Role guard + conflict guard on the real site | 10 followed readings → 0 uploaded; an xDrip+-style entry → `CONFLICT`; after confirming, uploads resume | `roles-and-conflict.txt` |
| Unit tests (in-memory Nightscout with 15.0.8's dedupe rules, a 14.x mode, v3 limits and immutable `date`) | **19/19** | `./gradlew :core:test` |

Not verified:
- **nightguard and LoopFollow running for real.** They are iOS apps and no simulator ran (the Mac is busy). Their
  requests and parsing rules were replayed instead, plus LoopFollow's real decoder.
- **A hosted Nightscout over TLS.** The brief excluded it. The TLS path is the platform's `HttpURLConnection`,
  unchanged.
- **A screenshot of the web UI.** Headless Chrome never goes idle on Nightscout's socket.io page. Its rendered title
  and the pills' data source (`/api/v2/properties`) are the evidence instead.

**Cost.** One reading is one POST of ~0.4 KB JSON. At 1/min that's ~1 440 requests and ~1–2 MB/day with headers
and TLS. The phone already wakes every minute for the sensor, and the upload rides that wake-up. During an outage
readings just queue (no extra wake-ups). Sukrara's own battery numbers belong to investigation 11.

## 5. Privacy and security

- **Secret/token at rest**: in Sukrara's existing `core/SecureStore`, an AES-256-GCM file whose key lives in the
  Android Keystore (TEE/StrongBox, non-exportable), the same as the owner's keys. Not in DataStore or prefs.
  `allowBackup=false` already keeps it out of backups. The UI shows it once on entry, then only "set" / "change".
- **On the wire**:
  - Only `https://`. `http://` is refused except for `localhost`/`127.0.0.1` (`NsConfig` init). Sukrara's
    `network_security_config.xml` already allows cleartext only to the loopback, so the platform enforces the same.
  - Redirects aren't followed, because a redirect could carry `api-secret` to another host.
  - The secret only ever travels as its SHA-1. The token travels once per JWT exchange, then only the short-lived JWT.
- **In logs**: `NsAuth.toString()` masks both. The CLI reads them from the environment, never argv. Sukrara's log
  lines must show `Bearer …`/`api-secret` masked too (QA step).
- **What leaves**: §3.4. Off by default, wearer only, and the settings page names the site it sends to.
- **Server side** (user advice in the setup screen): create the `sukrara-uploader` role + subject and paste the
  token, rather than the API secret, which is full admin on the site.

## In Sukrara

Not yet. Nothing from this investigation is in the app; the draft below is for the integration step.

## To bring into Sukrara

A **Tidepool upload is being built in parallel** (branch `w-tidepool`). Both are "my data to a cloud I chose": the
queue, backoff, diff-backfill, roles and conflict ideas here are written so they can sit next to it:
- The `Uploader` is generic over a **destination**: build documents, post a batch, list what the server has in a
  window, delete one. Nightscout and Tidepool would each implement that.
- The queue's shape (timestamps and ids, documents built at send time, persisted, `Health` states) can be shared
  by both, or at least copied.
- Agree with `w-tidepool` on one `CloudUploader` interface (`onReadings`/`onTreatmentChanged`/`onSensorStart`/`sync()`
  → `SyncReport`) and one status row design before either merges.

**Files** (Android, `spikes/showcase-android/app/src/main/kotlin/app/mauri/sukrara/`):
- `nightscout/` — port `NsConfig`, `NsDocs`, `NsClient`, `Uploader`, `Http` (unchanged: org.json + HttpURLConnection
  are in Android). `Model.kt` maps from `core/Proto.Reading` and `treatments/Treatment` (the treatment's `id` is
  already a UUID).
- `nightscout/NightscoutUpload.kt` — holds the config, stores the credential in a `SecureStore`
  (`nightscout-upload.bin`, alias `sukrara.nightscout`), keeps the queue state in `filesDir/nightscout-queue.json`,
  and runs `sync()` on a single-thread executor after each reading plus a periodic job (15 min) while the
  network is up.
- Hook it into `wearer/WearerExtras` (next to LibreView): `onReading`/`onHistory` → `onReadings`, the TreatmentStore's
  `onChange` → `onTreatmentChanged`, `onSensor` → `onSensorStart`, the battery broadcast → `onBattery`. **Wearer role
  only**; the follower engine doesn't call it (the relay option is a later step, off by default).
- `bridge/NightscoutApi.kt` (the read adapter) should take its treatment mapping from `NsDocs`. Today it serves
  long-acting insulin as a `Note` **with** `insulin`, which inflates IOB in any client that sums it.
- Strings in `values/` + `values-es/`; README *Share with apps*; `docs/FEATURES.md`; `docs/10-showcase.md`.

**Settings** (AOSP-style, Settings → Share with apps → a new group "Nightscout" above the existing local
"Nightscout API" rows; rename those "Nightscout API on this phone"):

| Row | Type | Default |
|---|---|---|
| **Upload to my Nightscout** | switch with summary: off → "Off"; on → `my-ns.example · last upload 1 min ago` / "Paused: another uploader (xDrip-LibreReceiver) is writing this site" / "Can't sign in: check the token" / "Waiting for network · 12 readings queued" | **off** |
| Site | text, `https://…` (validated, http only for localhost) | empty |
| Access token *or* API secret | password field; a link "How to make a token" (the role above) | empty |
| Test connection | action pill: `status.json` (+ JWT exchange), shows the server version and units | — |
| What to send | switches: readings · treatments · sensor changes · phone battery | all on |
| Upload anyway (shown only when paused by a conflict) | action pill | — |
| Advanced | API v1 / v3 (default v1), backfill 7 days | — |

**Added / discarded (draft for this report and docs/10):**

| Item | Decision | Why |
|---|---|---|
| Entries 1/min with `NONE` fallback, UTC/local `dateString` per API, v5 identifier | **Add** | every reader in §2 decodes them; identical documents across APIs |
| Treatments (rapid / carbs / notes) with Sukrara's UUID as `identifier`, edits/moves/deletes | **Add** | followers and reports see the same log as the app |
| Long-acting insulin as `Note` without `insulin` | **Add** | IOB in Nightscout/LoopFollow would otherwise count it as a bolus |
| `Sensor Change` + `Sensor Start` | **Add** | SAGE in nightguard *and* LoopFollow |
| Uploader battery devicestatus | **Add** | cheap; Nightscout pill, LoopFollow, nightguard |
| Queue + diff backfill + backoff + auth stop | **Add** | outage, late readings, wiped DB all heal |
| API v1 default, v3 optional; access token recommended, API secret accepted | **Add** | bulk insert; least privilege |
| Conflict guard (another `device` in the last 30 min) | **Add** | prevents two uploaders on one site |
| Reading treatments back / two-way sync | **Discard** | unsigned, no LWW, wrong semantics; a read-only overlay later if asked |
| Follower uploads | **Discard (default)**; explicit single-person relay later | someone else's data must not become "our" sensor |
| v3 soft deletes | **Discard** | v1 readers still show the treatment |
| `filtered`/`unfiltered`/`rssi`/`mbg`/calibrations | **Discard** | Libre 3 has none |
| HTTP to LAN hosts | **Discard (for now)** | TLS only; see open questions |

**QA steps** (add to `docs/QA.md`):
1. Fresh install → Settings → Share with apps → Nightscout: off. Turn on with an empty site → it asks for the site.
2. `http://192.168.x.y` → refused; `https://` with a wrong token → "Can't sign in", no further requests (check the
   server log), fix the token → resumes.
3. Local test site (`spikes/nightscout-upload/verify/nightscout.sh up`, reached via `adb reverse tcp:1337 tcp:1337` as
   `http://localhost:1337`): after enabling, the last 7 days appear; `followers/run.sh` and `cli check` pass against it.
4. Airplane mode 20 min → off: readings fill in, no gaps or duplicates (`cli check`). Kill the app while queued →
   after restart the queue still drains.
5. Add carbs, edit the amount, move it 30 min, delete it: Nightscout shows 1 → 1 (new amount and time) → 0
   documents. Long-acting 14 U: the IOB pill doesn't change.
6. Pair a new sensor: SAGE shows 0d0h in Nightscout, LoopFollow and nightguard.
7. Follower phone: the Nightscout upload group isn't offered; no request leaves it.
8. With Juggluco still uploading to the same site: status "Paused: another uploader"; Upload anyway → resumes.
9. `adb logcat` while uploading: no secret, token or JWT in clear.

## Open questions

- **LAN/self-hosted over plain HTTP** (a Raspberry Pi at `192.168.x.y`): allow it behind a warning, or require TLS
  (Tailscale / a reverse proxy)? The PoC and Sukrara's network config allow only the loopback.
- **Backfill horizon**: 7 days (PoC) or Juggluco's 30? Longer costs one bigger diff on setup only.
- **Upload cadence**: every reading (1/min, freshest followers) or coalesced to every 5 min to save radio wake-ups?
  Battery numbers need investigation 11.
- **Relay for followers** (a parent uploading a child's data): build it, and with which consent from the wearer
  (a Nostr-signed "allow relay" in the share)?
- **Shared `CloudUploader` with Tidepool**: one interface and status row, or two separate ones that only look alike?

## Run it

```sh
cd spikes/nightscout-upload
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
./gradlew :core:test :cli:installDist
verify/all.sh                      # fresh local Nightscout in Docker, every check above → evidence/*.txt
python3 -I verify/ha_check.py http://host.docker.internal:1337 spike-secret-0123   # with a Home Assistant container
verify/nightscout.sh down
```
