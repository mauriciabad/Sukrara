# 04 — Insulin, carbs and glucose to LibreView, from the phone and the Mac

*2026-10-15 · investigation report · follows [02 — direct sensor](02-direct-sensor.md)*

## Goal

The doctor reads **LibreView**, so whatever replaces Juggluco must keep feeding it: glucose, and the insulin /
carbs / notes the user logs. Juggluco does this today. Questions:

1. Can the phone app upload treatments (and glucose) to LibreView itself?
2. Can the **Mac** app do it too?
3. Can a treatment logged on either device show up on the other, and reach LibreView **once**?
4. Will the same data fit the Nostr sharing being explored in [03](03-nostr.md)?

## Result

| Question | Answer |
|---|---|
| Phone → LibreView | **Yes, proven.** GlucoBridge uploads treatments and (direct flavor) sensor glucose; both appear in LibreView's export under the spike device. |
| Mac → LibreView | **Yes, proven.** GlucoseBar uploads treatments with the same client logic, byte-identical payload. |
| Sync phone ↔ Mac | **Yes, proven** over the existing BLE link: either direction in ~1–2 s, edits/deletes on both sides, and changes made on both sides while disconnected all converge. |
| No duplicates in LibreView | **Yes, proven** with LibreView's CSV export: the same entry posted by phone, Mac and a test tool is one row; edits replace, deletes remove. |
| Nostr-ready | **By design.** A treatment is one JSON object with a stable id and a last-writer-wins version — a parameterized-replaceable Nostr event as-is. Not implemented here. |

```
                  ┌──────── treatments (BLE GATT, JSON) ────────┐
Libre 3 ──BLE──▶ GlucoBridge (Android)                        GlucoseBar (Mac)
                  │  glucose + treatments                         │  treatments
                  └────────────▶ LibreView ◀──────────────────────┘
                    one LibreView "device": same DeviceId, same record numbers
```

## How LibreView ingestion works (what Juggluco does)

Juggluco impersonates Abbott's **FreeStyle Libre 3 Android app**: it fetches Abbott's public app config (API base
URL + API key), logs in with the patient's email/password and a per-install `DeviceId`, then POSTs one JSON
document per upload — device settings plus `currentGlucoseEntries`, `scheduledContinuousGlucoseEntries` (5-min
history), `insulinEntries` (rapid/long units), `foodEntries` (grams), `genericEntries` (notes, sensor start).
Every entry carries a `recordNumber`; an edit or a delete (`"action":"deleted"`) is the same record sent again.

The exact bytes are in [`spikes/libreview/SPEC.md`](../spikes/libreview/SPEC.md); `lv.py` there is an executable
reference that generates a golden fixture both apps must reproduce byte for byte (they do).

Key design decisions:

- **One LibreView device for both apps.** A login token is tied to a `DeviceId`, and LibreView lists each
  `DeviceId` as a separate device. The phone generates the id and hands it to the Mac over BLE, so phone, Mac
  (and the `lv.py` test tool) are *one* device. That also makes the spike removable in one click (LibreView →
  My devices).
- **Deterministic record numbers, one per version.** Treatment `recordNumber = SHA-256("id:version")[0..5] << 8 | type`;
  glucose uses Juggluco's scheme (sensor serial hash `| lifeCount`), checked against Juggluco's own C++. Either app
  uploading the same version produces the same record, so re-uploads are harmless. LibreView keeps the *first*
  value of a record number, so an edit is "delete the uploaded version + add the new one" (as Juggluco does).
- **Treatment log = the sync unit.** `{id, kind, amount, time, note, version, deleted, origin, uploaded-version}`;
  merge = newer version wins, upload watermark only moves forward. The phone numbers every accepted change, the Mac
  follows that sequence with a cursor — so sync never depends on the two clocks agreeing.
- **Upload policy.** The device that created an entry uploads it at once; the other uploads it only if it's still
  pending after 2 minutes. Double uploads still happen (and did) — they must be, and are designed to be, harmless.

## What happened (timeline, 2026-10-15)

| Time | Step | Outcome |
|---|---|---|
| 21:16–21:25 | BLE sync, no LibreView: phone→Mac, Mac→phone, cross edit/delete, edits on both sides while disconnected | all converged (Mac cursor = phone seq, nothing unacked) |
| 23:01 | login with `SetDevice:false` | `status 20 wrongDeviceForUser` — the account was bound to Juggluco's device |
| 23:04 | phone: login with `SetDevice:true` (Juggluco's upload switched off first), upload 2 own entries | `status 0` |
| 23:09 | phone uploads the Mac's 2 entries after the 2-min grace (one is a delete of a never-uploaded row) | `status 0` |
| 23:11 | Mac logs in (`SetDevice:false` now succeeds — same DeviceId), uploads its own entry | `status 0`; phone's token kept working |
| 23:12 | edit-after-upload (Mac edits a phone entry) — uploaded by **both** Mac and phone 3 s apart | `status 0` ×2 |
| 23:12 | delete-after-upload (phone deletes a Mac entry) | `status 0` |
| 23:12 | deliberate duplicate: `lv.py` re-posts two unchanged entries as the same device | `status 0` |
| 23:13–23:26 | Juggluco force-stopped, direct flavor holds the sensor, glucose upload on | only 1-minute "current" readings went up — LibreView doesn't keep those as rows |
| 23:22 | **CSV #1** | dedupe ✅, delete ✅, **edit ignored** ❌ (0.5 U edited to 0.7 U still showed 0.5) → record number per version |
| 23:30–23:34 | direct flavor again, uploading the sensor's backfilled 5-min history from 22:45 (what Juggluco hadn't uploaded) | `status 0` |
| 23:35 | edit test (1.0 → 1.5 U, edited on the Mac) and delete test (5 g, deleted on the phone), new scheme | `status 0` |
| 23:49 | **CSV #2** | edit ✅ (one row, 1.5 U), delete ✅, glucose ✅ (6 rows 22:49–23:14, continuing Juggluco's 22:44) |

## Verification

LibreView's upload API answers `status 0` even when it later discards data (ingestion is asynchronous), so success
codes prove nothing about duplicates. The only raw read-back is the web app's **Download glucose data** CSV (behind
a captcha, so it's a manual step): it has one row per stored record with `Device`, `Serial Number`, record type and
insulin/carb/note columns.

Spike-device rows in the final export (device = "FreeStyle Libre 3", serial = the shared DeviceId):

| What was sent | Rows in LibreView |
|---|---|
| note: uploaded by the phone, re-posted unchanged by `lv.py` | 1 |
| 0.3 U: uploaded by the Mac, re-posted unchanged by `lv.py` | 1 |
| 0.5 U edited to 0.7 U under the *same* record number, uploaded by Mac and phone | 1 — **still 0.5** (edit ignored) |
| 1.0 U edited to 1.5 U with the per-version scheme | 1 — **1.5** |
| 1 U long and 5 g carbs, deleted after upload; 12 g deleted before upload | 0 |
| sensor glucose, 5-min history 22:49–23:14 | 6, values identical to the sensor's |
| sensor-start record | 1 empty "note" row — Juggluco's device has the same |
| 1-minute "current" readings (uploaded every minute 23:14–23:26) | 0 — they feed LibreLinkUp's live view, not the stored history |

So: re-uploading is idempotent per (device, record number), deletes work, edits need a new record number, and the
glucose that reports are built from is the 5-minute history (which trails the live reading by ~16 min).

## Gotchas found

- LibreView **ignores changed values under an existing record number** — see above.
- Backfilled history arrives out of order (after the first live reading); tracking "uploaded up to life count N"
  silently skipped it. Track the set of uploaded life counts instead.
- Abbott's config CDN returns **403 to Python's default User-Agent**; all clients now send the Android
  `Dalvik/2.1.0 …` User-Agent the phone sends anyway.
- The account is bound to one "active" device: a new device must log in with `SetDevice:true`, which logs the
  previous one (Juggluco) out of LibreView. Juggluco then re-takes it on its next upload, so **two uploaders with
  different DeviceIds fight**. Exactly one app may own the LibreView upload.
- The first `PUT nisperson` (active-sensor registration) after login answered `status 20 invalid session`; it
  succeeded on the next cycle a minute later.
- macOS: CoreBluetooth doesn't report the link as dropped when the phone app restarts; the Mac's 150 s silence
  watchdog reconnects, so post-restart sync takes up to ~3 min.
- An unbundled `swift run` binary reads UserDefaults domain `GlucoseBar`, not `dev.spike.GlucoseBar`.
- Spike shortcuts not to keep: the phone stores the LibreView password in plain app preferences; the
  impersonation (Libre 3 app identity, `Android/16` headers from the Mac) is what Juggluco does but is against
  Abbott's terms.

## Nostr (investigation 03)

[03](03-nostr.md) streams glucose from one uploader to any number of encrypted followers and lists treatments as
out of scope. They fit without changing anything 03 built — design in [SPEC §5](../spikes/libreview/SPEC.md#5-treatments-over-nostr--proposal-fits-protocolmd-of-investigation-03-not-built):

- **Same kind, same keys.** One addressable kind-30078 event per treatment (like 03's hourly chunks), opaque `d`,
  content = this log's JSON encrypted to 03's read key. Volume is a few events per day.
- **One difference: many writers.** Glucose has one uploader; treatments are created on the phone, the Mac, maybe a
  parent's phone. Each device signs its own changes with its own key, and the *identity* key of 07's PROTOCOL v2 authorizes
  writers (not the phone's key, which changes with the phone), so a follower link stays read-only.
- **Survives key rotation.** Event addresses derive from a stable stream id rather than the read key, and each event
  says which read-key epoch encrypted it (agreed with the Nostr session for 07's PROTOCOL v2).
- **The merge rule already fits.** Newest version wins, and it's the rule both apps already use for BLE — so an entry
  can arrive over BLE, Nostr, or both, in any order, and every device ends up with the same log.
- **LibreView stays with the phone + Mac pair**; Nostr-only followers never upload to it.
- **Nightscout:** 03's adapter returns empty `treatments`; it could serve this log instead (rapid → Correction Bolus,
  carbs → Carb Correction, note → Note), which is what xDrip+, Nightguard and LoopFollow read.

**Code check (2026-10-16):** this investigation's uncommitted changes replay onto `origin/main` (with 03 and 05) with
one conflict — `main.swift`, where both added wiring; resolution = keep both, and `ble?.treatmentSync` because 03
made the BLE transport optional. Mac app builds with 03's Nostr SDK and passes the fixture + merge tests; Android
juggluco flavor builds and passes its unit tests.

What building it would take: the phone's `TreatmentStore.onChange` becomes a listener list (BLE + Nostr), 03's
`NostrPublisher` (moving into GlucoBridge anyway) gains a treatment publish + subscribe; on the Mac, 03's
read-only `NostrSource` gains a device key (Keychain) and event publishing with 03's pacing rules.

## Cleanup (for the user's real account)

*Done 2026-10-16: spike uploads switched off in both apps, spike device removed in LibreView, Juggluco's upload
back on. Verified with a final export: 0 rows from the spike device, and Juggluco's own history continuous
(every 5 min, no gaps, no duplicates) through the spike window — it re-uploaded its backlog after being re-enabled.*

1. Delete every spike entry from the app (sends LibreView deletes) — or `lv.py cleanup`.
2. LibreView → Account settings → **My devices** → remove the **GlucoBridge** device: removes everything that
   device uploaded, glucose included. Irreversible, but it only holds spike data.
3. Juggluco → LibreView → **Send to LibreView** back on. It re-takes the account and uploads its own backlog
   (including the hours Juggluco was off), so the gap closes with Juggluco's data.
