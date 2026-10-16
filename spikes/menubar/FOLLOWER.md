# GlucoseBar as a protocol v2 follower (Nostr + Bluetooth)

*2026-10-16 · follows [PROTOCOL-v2](../nostr/PROTOCOL-v2.md), [06](../../docs/06-sharing-and-keys.md),
[07](../../docs/07-bluetooth-followers.md)*

The Mac now pairs and follows exactly like the Android "CGM Share" follower: no secret in the link, its own
follower key, a 4-digit match code on both screens, and the wearer taps Allow. v2 is the default; the old v1
`nostr` link (`NostrSource.swift`) and the GlucoBridge BLE source still work unchanged.

## How to use

- Menubar → **Follow someone…** → paste the `cgmshare:join?…` link (pre-filled if it's on the clipboard) and a
  name for this Mac (defaults to the computer name) → a window shows the **match code** until the wearer allows it.
- **Follow from QR image…** decodes a screenshot/photo of the share QR (CoreImage, no new dependency).
- Opening a `cgmshare:` link launches the dialog (URL scheme registered by `bundle.sh`).
- **Nearby (Bluetooth, no internet, no QR)**: when the wearer opens *Pair nearby*, GlucoseBar offers
  "Follow <phone> nearby?"; the request and the key travel over GATT, same match code.
- Menu line: "Following: waiting for approval · code 1234" / "Following (v2) · key epoch n" /
  "⚠︎ Access removed by the wearer". **Stop following** deletes the keys.
- Source label: `Nostr · <relay host>` or `Bluetooth · <phone name>`.

## Code

| File | What |
|---|---|
| `NostrV2.swift` | `V2` helpers (H, gk, match code, invite parsing, gift wrap), `RelayPool` (reconnects, outbox, NIP-42 AUTH as F on `auth-required`), `FollowerV2` — the **one `onEvent(json, via:)`** every transport feeds; all acceptance rules; treatment writer |
| `BLEV2Central.swift` | CoreBluetooth central for the docs/07 GATT service (frames `[msgId][idx][count]`, HELLO/INFO/EVENT, one write in flight, scan by service UUID, connect timeout, silence watchdog, pause when the phone has nothing for us) |
| `FollowUI.swift` | follow dialog, QR-from-image, pairing window (match code) |
| `Keychain.swift` | follower state (F nsec, epoch read keys, sid, roster) as one login-Keychain item — never UserDefaults, never logged |
| `V2SelfTest.swift` | `GlucoseBar -v2selftest`: genuine vs. forged events through `onEvent` |
| `main.swift` | `Following` controller, wiring, debug commands (`follow-status`, `readings`, `unfollow`, `v2-treat`, `follower-pub`) |

Acceptance rules (same as Python/Android): key deliveries and "revoked" only if the **seal signer == I**; roster
only if authored by I (newest wins); live/chunks only if author ∈ roster `devices`, `gk` is a known epoch,
id + signature verify and NIP-44 decrypts to `v == 2`; unknown `gk` → rekey request (max 1 per 5 min per gk);
events are deduped by id across relays and BLE only after verifying (a forged copy can't shadow the real one) and
only once their gk is known (so a backlog that arrives before the key is re-read after it).

## Test results (2026-10-16, real public relays and the real phone radio)

**Nostr, against the Python v2 owner** (`v2/cli.py owner --every 30`, separate process and storage; the only
shared thing is the relays):

| Test | Result |
|---|---|
| Pair (invite → request → auto-approve) | key delivery **< 1 s** after the request; match code **4507** on the Mac = 4507 in the owner log (second run 7165 = 7165) |
| Values | 13 chunks (723 readings, 12 h) + live; the 3 h in the menubar store: **183/183** sgv and rate equal to `synthetic()` |
| Rotation (owner revokes another follower, PyB) | owner epoch 2 at 03:47:26 → Mac saw the unknown gk, sent a rekey request, got epoch 2 at 03:47:30, re-read 13 chunks under epoch 2, live continued |
| Revoke this Mac | "Access removed" the same second as the owner's rotation to epoch 3; **0 readings accepted afterwards** |
| **Negative**: owner denies (`--deny`) | stays "waiting · code 3217", **0 events accepted** after 3 min |
| Restart | state resumed from the Keychain, 12 h backfill again in < 1 s |
| NIP-42 | relay.nostr.net answers AUTH with "relay needs serviceUrl" (known, see 06); the other 3 relays serve gift wraps |

**Acceptance self-test** (`-v2selftest`, 13/13 PASS): forged key delivery rejected; data before a key ignored;
genuine key accepted; live from a roster device accepted; live from a non-roster key that knows G rejected;
tampered content rejected (bad id/sig); unknown gk not accepted; roster signed by a non-identity key ignored;
identity-signed roster adding a device accepted; chunk decoding; forged "revoked" ignored; real "revoked" applied.
`-qrselftest`: invite → QR PNG → decoded back identical.

**Bluetooth, against CGM Share (inv07 debug owner) on the Pixel, real radio**, with relays switched off on the Mac
(`-v2NoRelays YES`) so everything had to come over GATT:

| Test | Result |
|---|---|
| Discovery → connected + INFO | 2–17 s after launch (MTU write 512 B) |
| Nearby pairing | phone notification "Mac v2 BLE test 2 wants to see your glucose · Code **0936**" = Mac's 0936, request received via `ble:`; approve → key delivery over BLE |
| Backlog + live | 26 hour chunks (~25 h) + live; **180/180** readings in the Mac store identical to the phone's `owner.db`; live readings arrive < 1 s after the phone publishes |
| Both transports at once | BLE copy first, relay copies deduped by event id |
| Another wearer's phone nearby (Mac follows the Python owner) | INFO shows a different identity → disconnect, retry in 10 min |
| Treatment writer (optional item 3) | before being made a writer: not sent; after `writer` on the phone the roster update reached the Mac (~50 s) and a **note** treatment was **accepted by the phone over BLE in 2 s** |

Release binary: 14.0 MB (`./bundle.sh`), unchanged order of magnitude (NostrSDK dominates).

## Not done / caveats

- **Rotation / revocation over BLE with the phone** not exercised (no headless "remove follower" command, and it
  would rotate the live stream). Same `onEvent` code as the relay tests above.
- `cgmshare:` URL open not exercised live (registered in Info.plist); QR-from-image only via the self-test, not a
  photo of the phone's QR.
- **Keychain + ad-hoc signing**: the item has the default ACL (only the creating app reads it silently). Each
  rebuild of the ad-hoc-signed app is a new identity → macOS asks once ("Always Allow"). A shipped app would be
  Developer-ID signed and use the data-protection keychain with an access group.
- No follower alarms (low/high/no data) on the Mac yet; no local reading DB (the store keeps 3 h, every launch
  backfills 12 h from relays / ~25 h over BLE).
- Two GlucoseBar processes on one Mac can't both hold a BLE link to the phone (the second times out and retries).
- Treatments: the Mac only *sends* (from "Log treatment…" when the roster lists it as a writer); edits/deletes and
  the phone's treatments are not synced back over v2.
- Test leftovers to clean up: on the phone, followers "Mac v2 BLE test", "Mac v2 BLE test 2", "Mac v2 writer test"
  (writer) and one note "GlucoseBar v2 writer test" in CGM Share's DB (Mac-side keys already deleted; removing them
  on the phone rotates the key). In the login Keychain, items `dev.spike.GlucoseBar.test1`, `.test6`, `.test6b`
  hold keys for the synthetic Python test owner only.
