# 07 — Bluetooth followers

*2026-10-16 · investigation report · builds on [06 — Sharing and keys](06-sharing-and-keys.md) · spec: [PROTOCOL-v2](../spikes/nostr/PROTOCOL-v2.md), treatments: [libreview SPEC §5](../spikes/libreview/SPEC.md)*

## Goal

1. **BLE as a second transport** for the *same* signed + encrypted v2 events (owner = GATT server, follower =
   central). Security from the events (signatures, roster, NIP-44 to `G_n`), not from BLE pairing. A follower with
   **no internet** must keep receiving.
2. **Nearby pairing over BLE**: no QR, no internet — the follower lists nearby owners, sends its access request over
   BLE, the owner sees the usual Allow dialog with the match code, the key delivery comes back over BLE.
3. **Followers as writers**: owner toggle "Can add entries" → follower key in roster `writers` → follower can add a
   treatment that the owner accepts only if the signer is a writer.

## Result

**All three work**, on real radio (Pixel owner ↔ Mac) and app↔app (two emulators via netsim), with the follower
emulator's Wi-Fi and mobile data **off** for the whole app↔app run. Transport code is ~400 lines on top of 06's
`Transport` seam; the engines' acceptance rules are unchanged — BLE events go into the same `onEvent(json, via)`.

| # | Claim | Devices | Result | Evidence (`spikes/nostr/android-app/evidence/`) |
|---|---|---|---|---|
| 1 | Owner advertises, follower discovers it by service UUID | Pixel → Mac; emu → emu | ✅ Mac finds it in 0.2–5 s; emulator lists it with RSSI | `07-mac-ble-pairing.log`, `07-follower-nearby-list.png` |
| 2 | Same v2 events over BLE, verified/decrypted by an independent implementation | Pixel (Juggluco) → Mac (`cgm2.py`) | ✅ roster + 25 chunks + live, **27/27 accepted, 0 rejected** | `07-mac-ble-treatment-rejected.log` (first lines) |
| 3 | Live readings pushed over BLE as they happen | Pixel → Mac | ✅ every minute, ≈ 1 s after Juggluco's reading | `07-mac-ble-writer-treatment-live.log` |
| 4 | Follower with **no internet** keeps receiving (BLE only) | emu → emu (follower offline) | ✅ 24 h of chunks + live every minute, all `via ble:` while every relay fails with `UnknownHostException` | `07-emu-follower-offline.log`, `07-follower-home-ble-offline.png` |
| 5 | Nearby pairing: request over BLE, Allow + match code, key delivery back over BLE | Pixel ↔ Mac; emu ↔ emu (follower offline) | ✅ code 1841 on both sides (Mac); emulator: request → Allow → `KEY accepted … via ble:` → backlog readable | `07-mac-ble-pairing.log`, `07-phone-owner.log`, `07-owner-allow-dialog-ble.png`, `07-follower-waiting-code-ble.png`, `07-emu-follower-nearby-pairing.log` |
| 6 | Non-writer's treatment is rejected | Mac (approved follower, not a writer) → Pixel | ✅ `treatment REJECTED: author Mac BLE … is not in roster writers` | `07-phone-owner.log` |
| 7 | Owner toggle → roster `writers` → follower learns it may write | Pixel → Mac; emu → emu | ✅ roster over BLE shows `writers [325d…]` / follower UI shows "Add entry" | `07-mac-ble-writer-treatment-live.log`, `07-emu-owner.log` |
| 8 | Writer's treatment accepted and stored by the owner | Mac → Pixel; offline emu → emu | ✅ `TREATMENT accepted from Mac BLE via ble:… rapid 1.5`; emulator: `rapid 2.5` shown under "Entries from followers" | `07-phone-owner.log`, `07-emu-owner.log`, `07-follower-add-entry.png`, `07-owner-writer-and-entry.png` |
| 9 | Follower reconnects by itself after the owner restarts (new private address) | emu → emu | ✅ after the fix below (re-scan by service UUID) | `07-emu-follower-nearby-pairing.log` |

## Numbers (real radio: Pixel owner ↔ MacBook, same room)

| | |
|---|---|
| Discovery (scan → found) | 0.2–5 s |
| Connect + service discovery | 4.6–4.9 s (one outlier 31.7 s) |
| ATT MTU | 517 requested by Android / 515 seen by bleak on macOS → **512-byte attribute limit applies** (506 B payload per frame) |
| `HELLO` → `INFO` | 63–121 ms |
| Backlog on connect (roster + 24–25 hour chunks + live, ~1.8–1.9 KB per chunk) | **47–52 KB in 2.2–2.4 s ≈ 19–22 KiB/s**; once 48 KB in 4.2 s (11 KiB/s) while a second central (the Mac menubar agent) was also connected |
| Live push latency | phone clock was 1.85 s ahead of the Mac; corrected, **≈ 0.9 s** from the phone's `created_at` to receipt on the Mac (second-resolution timestamps → ±1 s) |
| Treatment write → owner accepted | ≈ 0.1 s (skew-corrected) |
| Emulator ↔ emulator (netsim) | live readings `+0 s` when the owner app is in front; `+11…+18 s` later while the owner emulator sat in the background (owner-side source timing, not BLE) |

## Design

```
   follower (central)                                  owner phone (GATT server, advertises service UUID)
   ─ scan for service UUID ─────────────────────────►
   ─ connect, MTU 517, notify on TX, write HELLO ───►
   ◄──────────────────── INFO {i, relays, pair?} ────  pair = {token, expiry} only while "Pair nearby" is open
   ◄── EVENT roster, EVENT chunk ×24, EVENT live, EVENT recent gift wraps (backlog cache)
   ── EVENT gift wrap "request" (to I) ─────────────►  onEvent(json, "ble:<addr>") → same rules → Allow dialog
   ◄──────────────── EVENT gift wrap "key" (to F) ───  every publish() also goes to BLE peers
   ── HELLO (again, now the backlog is readable) ───►
   ◄── EVENT live … every minute
   ── EVENT treatment (kind 30078, signed by F) ────►  accepted only if F ∈ roster writers
```

- **One `Transport` per medium.** `BleOwner` and `BleFollower` implement 06's `Transport`; the engines call
  `publish()` on every attached transport and receive through `onEvent(json, via)` with `via = "ble:<addr>"`.
  Nothing BLE-specific is trusted: the owner's backup event is never sent over BLE; everything else is the same bytes
  as on the relays (the Mac verified signatures and decrypted with its own `G_n`).
- **Relays stay on.** A BLE follower still subscribes to the relays; whichever copy arrives first wins (`via` is
  stored per reading). With no internet the relay connections just fail and BLE carries everything.
- **Backlog cache on the owner** (in memory): last roster, last ~38 hour chunks (by `d`), latest live, last 10 gift
  wraps. Sent on every `HELLO`. Caching wraps means a follower that drops out during approval still gets its key
  when it reconnects. A follower re-sends `HELLO` after accepting a *new* key, so chunks received before approval
  become readable.
- **Nearby pairing token**: "Pair nearby (10 min)" creates a normal invite token (stored with the QR ones, single
  use, burnt on Allow/Deny) and exposes it in `INFO`. Anyone in range during those 10 minutes can *ask*; the owner
  still decides with the match code, exactly like the QR flow.
- **Writers**: `OwnerState.writers` (in the owner backup too) → `writers` array of the roster (only active
  followers; removed on revoke). Owner accepts a treatment if: valid signature, author ∈ writers and an active
  follower, `gk` known, decrypts with that epoch's `G`, `d` tag matches `H("cgm2:tr:"+sid+":"+id)`; stored in
  `owner.db` table `treatments` with LWW on `u`. Accepted BLE treatments are forwarded to the other BLE peers; the
  owner also subscribes on the relays to `#c` treatments from writers, so a writer far away works over relays too
  (code path in place; only the BLE path was tested).

## GATT framing

Owner phone = peripheral / GATT server (advertises the service UUID in the advertising packet, device name in the
scan response). Follower = central. **No BLE pairing/bonding**: anyone nearby may connect; trust comes only from
the v2 events themselves (signatures, roster, NIP-44 to `G_n`, gift-wrap seal signer), exactly as for relays.

| what | UUID | properties |
|---|---|---|
| service | `7c6a0001-5c3e-4f0a-9a57-c95e0b1e0007` | primary |
| RX (follower → owner) | `7c6a0002-5c3e-4f0a-9a57-c95e0b1e0007` | write (with response) |
| TX (owner → follower) | `7c6a0003-5c3e-4f0a-9a57-c95e0b1e0007` | notify (CCCD `0x2902`) |

**Frame** (each GATT write or notification): 6-byte header, big endian, then payload bytes

```
[msgId u16][idx u16][count u16][payload: up to ATT_MTU - 3 - 6 bytes]
```

- `msgId` increments per message per sender (wraps at 65535); `idx` = 0..count-1; frames are sent in order.
- Receiver keeps one buffer per `msgId`; a frame with `idx == 0` restarts that buffer. When all `count` parts are
  present, concatenate payloads → UTF-8 → one JSON array (the message).
- Central requests MTU 517 (Android) / uses `maximumWriteValueLength` (CoreBluetooth); the owner uses each
  central's negotiated MTU for its notifications (23 if never negotiated → 14 payload bytes per frame).
- Owner sends one notification at a time per central (next one after `onNotificationSent`); central writes one
  frame at a time (next one after the write response).

**Messages** (Nostr-wire style JSON arrays):

| message | direction | meaning |
|---|---|---|
| `["HELLO", {"v":2}]` | follower → owner | sent right after enabling notifications: "send INFO + what you have" |
| `["INFO", {"v":2,"i":"<I pubkey hex>","relays":[…],"pair":{"t":"<token>","x":<expiry>} \| null}]` | owner → follower | reply to HELLO. `pair` is non-null only while the owner has "Pair nearby" open (10 min); it is an invite token like the QR one (owner still has to Allow, match code shown on both screens) |
| `["EVENT", <signed nostr event>]` | both | any v2 event: live (21078), hour chunks / roster / treatments (30078), gift wraps (1059: access requests, key deliveries, rekey, revoked) |

After `HELLO` the owner sends `INFO`, then its cached events: the roster first, then up to ~38 recent hour chunks
(oldest → newest), then the latest live event. After that every event the owner publishes (live, chunks, roster,
key deliveries…) is also pushed to every subscribed central. The owner backup event is never sent over BLE.
Followers send access requests (gift wrap to `I`), rekey requests and treatments (kind 30078, see below) as
`EVENT`s on RX; the owner feeds them into the same `onEvent(json, "ble:<addr>")` as relay events.

**Treatments** (libreview SPEC §5): kind 30078, signed by the writer `W` (the follower key `F`), tags
`d = H("cgm2:tr:"+sid+":"+id)`, `c = H("cgm2:treatments:"+sid)`, `gk`, `expiration` (+30 d); content =
NIP-44(`W` → `G_n.pub`) of the §1 treatment JSON (`id,k,a,t,n,u,del,o`). The owner accepts it only if `W` is in the
roster `writers` (which it controls with the per-follower "Can add entries" switch) and is an active follower.

## Implementation

CGM Share (`spikes/nostr/android-app`, Kotlin), same app, both roles:

| file | what |
|---|---|
| `core/Ble.kt` | UUIDs, frame split / reassembly, `INFO` builder |
| `core/BleOwner.kt` | GATT server + advertiser, per-central notify queue (one in flight, `onNotificationSent`), backlog cache, `forward()` |
| `core/BleFollower.kt` | scan, connect, MTU, CCCD, `HELLO`, write queue, auto-reconnect with re-scan |
| `core/Proto.kt` | `treatmentEvent`, `treatmentsC/D`, roster `writers` |
| `owner/OwnerEngine.kt` | BLE start, "Pair nearby", `setWriter`, `onTreatment`, treatments relay subscription |
| `owner/OwnerState.kt`, `owner/OwnerDb.kt` | `writers` (persisted + backup), `treatments` table (DB v2) |
| `follower/FollowerEngine.kt`, `FollowerState.kt` | `joinNearby`, `addTreatment`, roster `writers`, stored owner BLE address |
| `ui/*` | owner: "Nearby (Bluetooth)" card, per-follower "Can add entries" switch, "Entries from followers"; follower: "Find phones nearby" list, "Add entry" (insulin / carbs / note) |
| `App.kt` | **debug-only** `dev.spike.cgmshare.CMD` broadcast (`owner`, `pair`, `approve`, `writer`, `fg`) to drive a locked phone over adb |
| `AndroidManifest.xml` | `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN` (`neverForLocation`) — runtime-requested at launch |

Mac test client: `spikes/nostr/ble/ble_follower.py` (`bleak`, uses `v2/cgm2.py` for every check). Follower secret in
`spikes/nostr/secrets/ble-follower.json` (gitignored, never printed).

Release APK built: `spikes/nostr/android-app/app/build/outputs/apk/release/app-release.apk` (14 MB; only the debug
build was run on devices).

## Bugs found (and fixed)

1. **512-byte attribute limit.** With MTU 517, frames of `MTU − 3 = 514` bytes made
   `notifyCharacteristicChanged` fail; frames are now capped at 512 bytes (506 payload) whatever the MTU.
2. **Roster missing from the BLE backlog after a restart** (it is re-published to relays only weekly) → a fresh
   BLE-only follower had no `writers`/devices update. The owner now re-publishes the roster when BLE starts.
3. **Rotating private addresses.** Android advertises from a resolvable private address that changes when the app
   restarts; reconnecting to the stored address failed with status 147 forever. The follower now re-scans by service
   UUID (first owner found) and falls back to the stored address. *For several owners in range it should check
   `INFO.i` before trusting the link for writes — events are verified anyway.*
4. **Re-`HELLO` loop** in the Mac client: the backlog contains cached key wraps, so "new key → HELLO" looped. Only
   a key with an unknown `gk` triggers `HELLO` now (the Android engine already ignores repeated keys).
5. **CoreBluetooth stale identifiers**: the Mac sometimes got a peripheral id that never connected (30 s timeout)
   while another Mac process (the menubar agent) held a link to the phone; the client retries with a fresh scan.
6. Test-harness note: the phone's `main` log buffer is 256 KiB and rotates within minutes — capture with
   `adb logcat -T 1 -s CgmShare.owner:I > file` from the start.

## Not done / open

- **Forged-key treatment over BLE from the Mac** (random key, not a follower) did not complete: three connect
  timeouts (bug 5). The equivalent rejection path is covered by the real non-writer follower (row 6), which is the
  stricter case (valid follower, valid epoch, only `writers` membership missing).
- Writer treatments **over relays** (follower away from the owner) — code path present, not tested.
- Followers don't show treatments (the owner forwards them; follower ignores them). No edits/deletes UI, no
  LibreView upload (showcase app wires investigation 04's uploader).
- **No GATT-level abuse limits**: anyone in range can connect, receive the encrypted stream, see `I`'s public key
  in `INFO` and write events (verified, but CPU cost). Add a per-peer rate limit and drop peers that send garbage.
- Privacy: while BLE is on, `INFO` reveals the owner's identity pubkey to anyone nearby, and the encrypted events
  reveal activity timing. Consider gating `INFO.i` behind "Pair nearby" and advertising only while followers exist.
- Battery cost of advertising + GATT server with a follower connected all day: not measured (see investigation 11).
- Screen-off/Doze behaviour of the follower's background re-scan (Android limits background scans) and the owner's
  advertising over hours: not tested; only ~30 min runs.
- Two followers on one owner over BLE at once: worked incidentally (Mac test client + menubar agent), not measured.
- Release APK not run on a device.

## Mac follower (GlucoseBar) — same pairing as Android

<img src="../spikes/menubar/evidence/ble-nearby-waiting.png" width="360" alt="Mac waiting for approval with match code">

GlucoseBar now follows a wearer exactly like the Android follower — over **Nostr v2** ("Follow someone…" with the
invite link or a QR image, match code, Allow) and over **Bluetooth v2** (nearby pairing with the same match code,
the GATT framing above). Keys live in the macOS Keychain. Notes and numbers: [`spikes/menubar/FOLLOWER.md`](../spikes/menubar/FOLLOWER.md).

| Test | Result |
|---|---|
| Nostr, against the Python v2 owner | approved < 1 s, same code on both sides; 183/183 readings equal; rotation → rekey in ~4 s; revoke → "Access removed"; denied → 0 data; state survives restart (Keychain) |
| Bluetooth only, against the Pixel owner | code 0936 on both; key + 26 hour-chunks + live over BLE; **180/180 readings equal the phone's DB**; live < 1 s; with both transports the BLE copy wins, relay copies deduplicated |
| Writer | a Mac note accepted by the phone over BLE in 2 s |
| Forgery self-test (`-v2selftest`) | 13/13 forged inputs rejected (forged key delivery, non-roster author, tampered content, fake roster, forged revoke, unknown epoch, …) |

Not done: rotation/revoke over BLE with the phone, opening a `cgmshare:` link live, Mac alarms and a local DB. Ad-hoc
signing means macOS asks once for Keychain access after each rebuild.

## For the showcase app (bundling all features)

- Copy `core/Ble.kt`, `core/BleOwner.kt`, `core/BleFollower.kt`; attach them as extra `Transport`s exactly as here.
  Keep the GATT framing above — the Mac menubar implements the same (another agent, `spikes/menubar`).
- Frames ≤ 512 bytes; one notification in flight per central; one write in flight on the central.
- Re-publish the roster when BLE starts; cache recent gift wraps; re-`HELLO` after a new key.
- Reconnect by re-scanning the service UUID, not by stored MAC address.
- Manifest needs the three BLUETOOTH_* permissions (runtime) and the FGS type `connectedDevice` it already has.
- Drop the debug `CMD` receiver (it's `BuildConfig.DEBUG`-gated here, but exported).

## Device state left behind

- **Pixel `62240DLCH0005W`**: CGM Share **debug** build installed for the first time (Juggluco and GlucoBridge
  untouched; Bluetooth/Wi-Fi not toggled). Role **owner**, new identity `npub1ugv8n…jq9h` (epoch 1), source
  **Juggluco** (127.0.0.1:17580), foreground service running, **BLE advertising on**, publishing to the 4 relays.
  Followers: two "Mac BLE" entries (`9fc0…` — an abandoned first test key, approved by the test harness — and
  `325d…`, the Mac test client, **writer = on**). One treatment stored ("rapid 1.5 mac writer test").
  Phone screen is locked: drive it with `adb shell am broadcast -a dev.spike.cgmshare.CMD -p dev.spike.cgmshare
  --es c pair|approve|writer …`.
- Emulators `emulator-5554` (owner, synthetic) and `emulator-5556` (follower, joined over BLE) still running;
  follower network re-enabled at the end.
