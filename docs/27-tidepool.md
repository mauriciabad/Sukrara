# 27 — Tidepool upload

*2026-10-19 · research + integration into Sukrara (Android) · source read from local checkouts in `third_party/`:
TidepoolKit `290bd04` (2026-06-01), LoopKit TidepoolService `99ac354` (2026-09-26), Tidepool shoreline `83cbea9`
(2026-09-12), Tidepool platform `cb7e01e` (2026-10-09, sparse), Tidepool message-api `16865fe`, AndroidAPS `aa16db6419`,
xDrip+ `8532095` · live API: `api.tidepool.org` with the user's account and synthetic data only*

## Goal

docs/22 listed Tidepool upload as gap #7 (deferred). The user asked for it: Sukrara uploads the wearer's readings and
treatments to the user's Tidepool account, off by default, with a queue that survives outages, and without borrowing
another app's identity.

## Short answer

- **Auth: Tidepool's own session login, `POST /auth/login`** (Basic email:password → `X-Tidepool-Session-Token`,
  refreshed with `GET /auth/login`). It needs no client id. The password is used once and never stored; the token and
  the account's user id are kept in a Keystore-encrypted file.
- **The OAuth path (Keycloak, PKCE) needs a client id registered with Tidepool.** TidepoolKit takes it from the host
  app, Loop/Trio/xDrip+/AAPS each ship their own (`aaps`, `xdrip`, the Info.plist `TidepoolServiceClientId`). Sukrara
  has none, and using another app's id would be impersonation (same rule as the Dexcom certificate in docs/23). Getting
  one means contacting Tidepool (partner / "Tidepool-compatible" program); see *For the user*.
- **Data model:** one `continuous` upload record per account, install and role, deduplicated by `origin.id`
  (`org.tidepool.deduplicator.dataset.delete.origin`). Readings → `cbg` (mg/dL, one per 5-minute slot); carbs → `food`;
  insulin → `insulin` (injections, acting type rapid / long); notes → Tidepool notes (message service).
- **Queue:** no separate outbox; each cycle compares the phone's 31-day store and treatment log with what Tidepool
  already has, so outages and late sensor backfills fill themselves in.
- **Verified** end to end against the production API with a synthetic night across a DST change, then deleted (see
  *Verification*): sessions last 12 min and refresh within a sliding 8 h window; Tidepool keeps a deleted upload
  record as an empty tombstone that the API can't remove.

## Research

### How third-party apps authenticate today

| App | Auth | Client id | Source |
|---|---|---|---|
| TidepoolKit (Tidepool's iOS SDK) | OAuth 2 authorization code + PKCE on Tidepool's Keycloak (`/info` → issuer → `.well-known/openid-configuration`), scopes `openid offline_access`; refresh with `grant_type=refresh_token` | supplied by the host app | `Auth/OAuth2Authenticator.swift:66-116`, `TAPI.swift:168-279` |
| LoopKit TidepoolService (Loop, Trio) | TidepoolKit | `TidepoolServiceClientId` in the host app's Info.plist ("client-id-not-in-info-plist" otherwise) | `TidepoolServiceKit/BuildDetails.swift:28-39` |
| AndroidAPS | AppAuth, PKCE | `aaps`, redirect `aaps://callback/tidepool` | `plugins/sync/…/tidepool/auth/AuthFlowOut.kt:47-50` |
| xDrip+ | AppAuth, PKCE | `xdrip`, redirect `xdrip://callback/tidepool` | `tidepool/AuthFlowOut.java:41-48` |
| Tidepool web (blip) | Keycloak JS | `blip` | `app.tidepool.org` bundle |

The issuer is `https://auth.tidepool.org/realms/tidepool` (`GET https://api.tidepool.org/info` →
`{"auth":{"realm":"tidepool","url":"https://auth.tidepool.org"}}`); a redirect-URI client has to be registered in that
realm, which only Tidepool can do.

The legacy endpoint is still in production and is not tied to a client: shoreline's `POST /auth/login`
(`user/api.go:606-638`) does a Keycloak password grant with **shoreline's own client** and packs access + refresh
token into one session token (`keycloak/token.go:14-31`); `GET /auth/login` refreshes it (`user/api.go:150, 679-722`).
A login is refused with 403 until the email is verified. Long-lived tokens are only handed out to user agents starting
with `axios/`, `okhttp/`, `Tidepool/` or `Loop/` (`user/api.go:101-104, 640-646`: Tidepool Mobile and Loop). Sukrara
sends its own `User-Agent: Sukrara/<version>` and gets the normal session; it does **not** spoof one of those prefixes.

### Platform data API (what Sukrara uses)

| Call | Use |
|---|---|
| `POST /v1/users/{userId}/data_sets` | create the upload record (type `upload`, `client{name, version}`, `dataSetType: continuous`, `deduplicator`, `deviceId`, `deviceManufacturers`, `deviceModel`, `deviceTags`, `time`, `computerTime`, `timezone`, `timezoneOffset`, `timeProcessing: none`) |
| `GET /v1/users/{userId}/data_sets?client.name=…` | find it again (by our `deviceId`) |
| `POST /v1/data_sets/{id}/data` | add data (JSON array; Sukrara sends ≤ 500 per request) |
| `DELETE /v1/data_sets/{id}/data` | delete data by selector `[{"origin":{"id":…}}]` |
| `DELETE /v1/datasets/{id}` | delete a whole upload record (tests, cleanup) |
| `GET /data/{userId}?uploadId=…` | read back (verification only) |
| `POST /message/send/{userId}`, `PUT /message/edit/{id}`, `DELETE /message/remove/{id}`, `GET /message/notes/{userId}` | Tidepool notes (message-api `lib/messagesService.js:46-61`) |

Every request also carries `X-Tidepool-Client-Name: app.mauri.sukrara` and `X-Tidepool-Client-Version` (as AAPS
does). The data set's `client.name` is free text (`data/data_set.go:92-128`: "TODO: Add OAuth client id to
DataSetClient when available"), reverse-DNS by convention; `client.version` must be non-empty (semver expected).

Validation rules that shaped the mapping (platform `cb7e01e`):

- Base datum (`data/types/base.go`): `time` required (RFC 3339); `deviceTime` as `2006-01-02T15:04:05`;
  `timezoneOffset` in minutes; `timezone` an IANA name; `notes` ≤ 100 strings of ≤ 1000 chars; `origin.id` / `name` /
  `version` ≤ 100 chars (`origin/origin.go:61-68`).
- `cbg`: `units` `mg/dL` or `mmol/L`; `trendRate` only with `trendRateUnits` (`mg/dL/minute`), `sampleInterval` in ms
  (`data/types/blood/glucose/continuous/continuous.go:77-84`).
- `food`: `nutrition.carbohydrate.net` and `units: grams` required (`data/types/food/carbohydrate.go:54-60`).
- `insulin`: `dose.total` + `units: Units` required, `formulation.simple.actingType` one of rapid / short /
  intermediate / long (`data/types/insulin/dose.go:55-60`, `simple.go:50-54`). This is the type for injected insulin;
  Loop's TidepoolService maps manually entered doses to it (`Extensions/DoseEntry.swift:110-125`), and Tidepool's viz
  draws it as a manual bolus (`tidepool-viz src/utils/DataUtil.js:789-793`). `bolus` / `basal` describe pump delivery
  (duration, rate, schedule) that a pen doesn't have.
- Deduplicator `org.tidepool.deduplicator.dataset.delete.origin`: for each new datum with an `origin.id`, the data in
  the same upload record with that origin id is deleted first (`data/deduplicator/deduplicator/data_set_delete_origin.go`,
  `…_base.go:51-78`). So the origin id is the dedupe key, across types.

### Limits

No documented rate limit; Sukrara sends at most one cycle every 5 minutes (plus one per treatment change), batches of
500, and backs off 1 → 60 minutes on errors. `cbg` values must be within the unit's range (0–1000 mg/dL).

## Design in Sukrara

| Part | Choice | Why |
|---|---|---|
| Upload record | one per account + install + role, `continuous`, deduplicated by origin; device `Sukrara-wearer-<8 hex>` / `Sukrara-following-<8 hex>`, model `Sukrara` / `Sukrara (following)`, tag `cgm` | TidepoolService's pattern (one open data set per client); a follower's upload stays apart from a wearer's on the same account |
| Readings | `cbg`, mg/dL, the earliest reading of each 5-minute UTC slot, origin `sukrara-cbg-<slot>`, `sampleInterval` 300 000 ms, trend rate | Tidepool counts samples (time in range, CGM use); same 5-minute rule as the CSV export (docs/22). A slot is idempotent: a later backfill of the same slot replaces it |
| Carbs | `food`, `nutrition.carbohydrate.net`, grams, origin `sukrara-tr-<id>` | TidepoolService's `SyncCarbObject` mapping |
| Insulin | `insulin`, `dose.total` Units, `formulation.simple.actingType` rapid / long | see above |
| Notes | an entry's note → the datum's `notes`; a note on its own → a Tidepool note (`/message/send`), edited / removed with it | Tidepool has no note datum; its notes live in the message service and show on the daily view |
| Time | `time` UTC, `deviceTime` local, `timezoneOffset` from the zone's rules at that instant, `timezone` IANA, `conversionOffset`/`clockDriftOffset` 0 | the phone's clock is the reference; the repeated hour when the clocks go back stays apart |
| Queue | state = sent slots (as ranges), sent treatment versions, note ids, failures; plan = store minus state | no outbox to lose; outages and backfills fill themselves in; a deleted-before-sent entry is never sent |
| Backfill | the first cycle starts 31 days back (what OwnerDb keeps) | "backfill from the existing store" |
| Retry | 1, 2, 4 … 60 min; the readings' progress is saved per batch | |
| Session | token + user id in `SecureStore` (`tidepool.bin`, AES-GCM key in the Android Keystore); password never stored | the flow doesn't need it: `GET /auth/login` refreshes |
| Roles | wearer: always (once on); follower: only with *Upload the person you follow* (default off) | a follower uploading someone else's data to its account is a separate decision |

Self-contained (`tidepool/` package): investigation 25 designs a generic upload queue for Nightscout; `TidepoolQueue`
(plan / applied / backoff) and `TidepoolSync` (send a plan) are pure Kotlin so the two can be unified later.

## Verification

- Unit tests (`TidepoolDataTest`, 10; `TidepoolQueueTest`, 13): units, trend, time fields in summer / winter, the
  repeated hour on 25 Oct 2026 (same `deviceTime`, offsets 120 / 60, different `time` and origin), spring forward,
  one-per-slot thinning, food / insulin / note mapping, data set fields, dedupe keys; queue: nothing sent twice, a gap
  filled later is backfilled, the 31-day window, edits / deletes / deleted-before-sent, notes and kind changes, switches,
  backoff 1 → 60 min, role gating, range compression and pruning.
- End to end against production (`TidepoolLiveTest`, run by hand with the credentials from a gitignored file; skipped
  in CI): see the run below.

**Run on 2026-10-19 against `api.tidepool.org`** with the user's account (after a password fix; the first attempts
were refused), synthetic data only, five runs (four stopped at a check, each fixed below):

| Check | Result |
|---|---|
| Login `POST /auth/login` (UA `Sukrara/…`) | 200, session token |
| Session lifetimes (decoded `exp − iat`, no token kept) | access (Bearer) **720 s** (12 min), refresh **28 800 s** (8 h) |
| Refresh `GET /auth/login` after 20 s | 200; both expiries moved by +26 s: the 8 h is a sliding idle window, kept alive by the 5-minute cycle. Keycloak's absolute session maximum isn't visible in the token; if it ends the session the status asks to sign in again |
| Data set create (`continuous`, delete-origin deduplicator) | 200 |
| 240 one-minute readings over 22:00–02:00 UTC on 30/31 Oct 2021 (clocks went back in Madrid) | 48 `cbg` stored, `timezoneOffset` 120 and 60 both present; each value = the slot's earliest reading |
| Units | Tidepool stores **mmol/L** (`7.71554` for 139 mg/dL, factor 18.01559); every mg/dL value round-trips exactly |
| Treatments | 1 `food`, 2 `insulin` stored |
| Note on its own | Tidepool note created (`/message/send`), visible in `/message/notes` |
| Same 48 slots re-sent with other values | still 48 `cbg` (origin dedupe) |
| Carbs edited, rapid deleted, note edited | 1 `food` with the new amount, 1 `insulin` left, the note text changed |
| Cleanup | notes removed, data sets deleted; `GET /v1/users/{user}/data_sets?client.name=app.mauri.sukrara` → **none**; notes in the window → **none**; `GET /data/{user}` → **no data**, only the five deleted upload records (`deletedTime` set) |

What the run exposed and fixed:

- **message-api requires `guid`** (400 `{"guid":"property is required"}`): notes now carry a stable UUID per entry
  (`TidepoolData.note`, unit test).
- Tidepool answers **mmol/L** when read back: the test converts with Tidepool's factor instead of expecting mg/dL.

**Not removable through the API: the deleted upload records.** `DELETE /v1/datasets/{id}` deletes the data and keeps
the record itself as a tombstone with `deletedTime` (platform `DeleteDataSet`); a second DELETE answers 200 and changes
nothing, and `GET /data` still lists the five (type `upload`, device `Sukrara-e2e-test-…`, version `0.0.0-e2e`, no
readings). The same happens when an upload is deleted in Tidepool web. Removing them for good needs Tidepool support.

## For the user

- **What you need to do:** sign in on the Tidepool page with your Tidepool email and password, then switch *Upload to
  Tidepool* on. Your email must be verified on Tidepool first (Tidepool refuses the login until it is).
- **If Tidepool already gets your Libre data from LibreView** (Tidepool's device connections), switch *Glucose readings*
  off in Sukrara, or every reading shows twice (two devices).
- **Five empty test upload records** (`Sukrara-e2e-test-…`, deleted, no data) stay on the account as Tidepool's
  tombstones; only Tidepool support can purge them.
- **The session can expire** (Tidepool's Keycloak decides; see the token lifetimes above). Then the status says
  "Signed out by Tidepool: sign in again"; nothing is lost, the queue catches up after you sign in.
- **Next step for a proper integration:** ask Tidepool for an OAuth client id for Sukrara (redirect
  `app.mauri.sukrara://callback/tidepool`), like Loop, xDrip+ and AAPS have. With it, sign-in moves to Tidepool's own
  web page (PKCE, `offline_access` refresh token) and the app never sees the password. Swapping the auth is local to
  `TidepoolClient.login/refresh`.

## In Sukrara

| Added | Where |
|---|---|
| Settings → Share with apps → Tidepool (both roles): account dialog, main switch, status, glucose / entries switches, follower's *Upload the person you follow* | `ui/TidepoolScreen.kt`, `ui/SettingsScreens.kt` (Share with apps row, summary), `ui/MainActivity.kt` (routes), en + es strings |
| Mapping to Tidepool's data model | `tidepool/TidepoolData.kt`, `TidepoolDataTest` |
| Queue (plan, applied, backoff, role gating) | `tidepool/TidepoolQueue.kt`, `TidepoolQueueTest` |
| API client (session login + refresh, data sets, data, notes) | `tidepool/TidepoolClient.kt` |
| Sender shared by the app and the end-to-end test | `tidepool/TidepoolSync.kt`, `TidepoolLiveTest` |
| Upload loop, Keystore-encrypted session, per-role state | `tidepool/Tidepool.kt`, started by `WearerExtras` / `FollowerExtras` |

| Discarded | Why |
|---|---|
| OAuth / PKCE sign-in | needs a client id registered with Tidepool; Sukrara has none and won't use another app's |
| A long-lived-token user agent (`okhttp/`, `Loop/` …) | those prefixes identify Tidepool Mobile and Loop to shoreline; spoofing them is impersonation |
| Storing the password for silent re-login | not needed while the refresh works; an expired session asks the user to sign in |
| `bolus` / `basal` for pen insulin | pump-delivery types (duration, rate, schedule); `insulin` is Tidepool's type for injections |
| `wizard` records for carbs (AAPS) | AAPS adds a fake 0.0001 U bolus to carry carbs; `food` is the real type |
| Every 1-minute reading | Tidepool counts samples; 5-minute slots match LibreView's historic rate and the CSV export |
| Mac uploader | not this round (parity-skip): the phone has the store, the queue and the account; the Mac would upload the same data twice |
