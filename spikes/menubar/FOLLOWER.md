# Sukrara for Mac (formerly GlucoseBar) as a protocol v2 follower (Nostr + Bluetooth)

*2026-10-16 · follows [PROTOCOL-v2](../nostr/PROTOCOL-v2.md), [06](../../docs/06-sharing-and-keys.md),
[07](../../docs/07-bluetooth-followers.md)*

The Mac now pairs and follows exactly like the Android "CGM Share" follower: no secret in the link, its own
follower key, a 4-digit match code on both screens, and the wearer taps Allow. v2 is the default; the old v1
`nostr` link (`NostrSource.swift`) and the GlucoBridge BLE source still work unchanged.

## Sukrara for Mac (2026-10-16, later)

GlucoseBar is now **Sukrara for Mac**: `./bundle.sh` builds `build/Sukrara.app` (executable and name "Sukrara",
bundle id **`app.mauri.sukrara.poc`**, the brand icon `spikes/brand/assets/sukrara/macos/AppIcon.icns`, tooltip and
"Quit Sukrara"). The Swift package/target keeps the name `GlucoseBar` (`swift run GlucoseBar` still works).

- **Invite links (0.11, investigation 14)**: only the v3 short link `https://sukrara.mauri.app/join#<32 chars>`
  (`V2.parseLink`). The follow dialog, clipboard prefill, "Follow from QR image…" and `-followLink` look the invite up
  on the relays first (`V2.resolveInvite`: gift wrap to the invite key, seal checked against the link's fingerprint),
  so the Mac knows who it is following before it asks. Opening links: `sukrara:` is the registered URL scheme; an
  https link opens in the browser (no Universal Links without a Team ID), where the join page's **Open in Sukrara**
  button hands it over as `sukrara:join#<32 chars>`. The old `#i=…&t=…` and `cgmshare:` forms are gone.
  `-qrselftest [-vectors <dir>]` checks the QR round trip, the link codec and invite key against the Python vectors,
  the rejection of the old forms, and the avatar "dots" against `spikes/nostr/identity/avatar-v1.json`.
- **Names and pictures (0.11)**: the request carries this Mac's name and a random generated picture (`Avatar.swift`,
  same drawing as Android's `core/Avatar.kt`); the wearer's name and picture come with the invite and every roster.
  Settings › Following and the pairing window show them.
- **Migration** (`Migration.swift`), once, on the first launch under the new id: copies the `dev.spike.GlucoseBar`
  defaults domain (missing keys only), `~/Library/Application Support/GlucoseBar` → `…/Sukrara` (treatments,
  LibreView ledger) and, on a background thread, the follower Keychain item `dev.spike.GlucoseBar.follower` →
  `app.mauri.sukrara.poc.follower`, then resumes following without re-pairing. Reading the old item makes macOS ask
  once ("Sukrara wants to use … in your keychain"); because that runs off the main thread, the menubar and the
  Bluetooth sources work while the prompt waits. The old items are left in place (delete by hand once happy:
  `defaults delete dev.spike.GlucoseBar`, Keychain Access → `dev.spike.GlucoseBar.follower`).
  Verified on this Mac: `migration from GlucoseBar: defaults: copied lvEmail, lvTakeover, lvUpload; keychain:
  follower state copied; files: Application Support/GlucoseBar copied to Sukrara`, then `v2 live … via relay:` and
  `via ble:Tartaruga` from the same wearer (epoch 1), no re-pairing.
- **Log file**: the bundled app also writes `~/Library/Logs/Sukrara.log` (an app started with `open` has no stdout).
- **Alerts** (`Alerts.swift`, `AlertsUI.swift`; replaced the old `Alarms.swift`, see the alerts section below).

## One Bluetooth link, treatments over v2, writer gating (2026-10-16, later)

- **One link per phone** (`BLELink.swift`): one `CBCentralManager`; connects are serialised (25 s timeout, that
  address skipped 60 s); a waiting path first checks the links it already has and `retrieveConnectedPeripherals`.
  (It carried the legacy GlucoBridge service too until v0.10.9, when that link was removed from both apps.)
- **Treatments for the follower** (SPEC §5, PROTOCOL-v2 "Treatments"): `#c` subscription from roster writers ∪
  devices (30 d); accepted iff valid signature, author ∈ writers ∪ devices, known `gk`, decrypts, `d` matches the id,
  known kind; merged (§1 LWW, by id) into the `TreatmentStore` with this Mac's own entries, so the panel's chart and
  Glucose Report › Treatments show one log. The phone uploads to LibreView; the Mac doesn't.
- **Writer gating**: the panel's log field is replaced by "The wearer hasn't allowed this Mac to log treatments"
  when this Mac isn't a roster writer; Settings › Following says where entries go. Following no one, entries stay on
  this Mac. Per entry: `v2 sending… → sent → accepted ✓` (wearer's device key echoes the id with u ≥ ours) or
  `v2 not accepted yet` (no echo after 2 min), in Glucose Report › Treatments (details as the row's tooltip).
- **Chart**: insulin (rapid = filled blue triangle, long = outlined indigo) with "4.5U" along the top, carbs
  (amber dot, "30g") along the bottom, notes as a dashed grey line — `evidence/n-menu-treatments.png`
  (today: `Sukrara -panelPreview <dir>`). The macOS widget views draw the same (`evidence/n-widget-*.png`).
- Tests: `-v2selftest` 33/33 (20 new treatment checks), `-v2relaytest` (throwaway wearer on 3 public relays:
  receive → merged, own entry OK → sent, echo → accepted), `-treatmentsselftest`.

## How to use

- Settings → Following → **Follow with Invite Link…** (or the panel's **Follow Someone…** before any data) → paste the `https://sukrara.mauri.app/join#…` link (pre-filled if it's on the clipboard) and a
  name for this Mac (defaults to the computer name) → a window shows the **match code** until the wearer allows it.
- **Follow from QR Image…** (same pane) decodes a screenshot/photo of the share QR (CoreImage, no new dependency).
- Opening a `sukrara:join#…` link launches the dialog (URL scheme registered by `bundle.sh`).
- **Nearby (Bluetooth, no internet, no QR)**: when the wearer opens *Pair nearby*, GlucoseBar offers
  "Follow <phone> nearby?"; the request and the key travel over GATT, same match code.
- Menu line: "Following: waiting for approval · code 1234" / "Following (v2) · key epoch n" /
  "⚠︎ Access removed by the wearer". **Stop following** deletes the keys.
- Source label: `Nostr · <relay host>` or `Bluetooth · <phone name>`.

## Glucose alerts (2026-10-17, investigation 15)

Same rules and settings JSON as Sukrara for Android ([alerts SPEC](../alerts/SPEC.md)): very low / low / high /
very high / extremely high (one at a time, escalation fires at once, lows strictly below the threshold), falling /
rising fast, no data; episodes that survive a brief exit, re-alert every `repeatMin`, readings > 15 min old never
alert; highs muted after rapid insulin and when the trend brings it back under the high threshold soon, lows after
carbs; when a treatment's window ends it alerts at once. Checked by the shared timelines (`-alerts-selftest`).

- **Menu → Settings…** (⌘,) → **Alerts** (HIG macOS settings: preference toolbar, no minimize/zoom, changes apply at
  once): master switch, state line ("High · muted: insulin 12 min ago"), alert source, one row per kind (name, summary,
  switch, **Details…** sheet with threshold / repeat / sound + ▶︎ / Like an alarm), quiet highs and lows, sound pack +
  "Different sound each time".
- **Settings**: one JSON string, `defaults read app.mauri.sukrara.poc alertSettings` (SPEC §3 keys and defaults; any
  missing key keeps its default). The old `alarms` bool is gone.
- **Alert source**: *Same as the wearer* (default) mirrors the wearer's kind **21079** `alert` events (relays: the
  `l` subscription is now kinds `[21078, 21079]`; Bluetooth: the same `FollowerV2.onEvent`), with the live acceptance
  checks, deduped by event id, ignored when `at` is > 10 min old; `fire` posts the kind with this Mac's sound, `clear`
  removes it. `no_data` still runs here. Without an approved v2 follow (legacy BLE / Juggluco Wi-Fi only) there is
  nothing to mirror, so this Mac's rules run and the window says so. *This Mac's settings* runs every rule locally.
- **Notifications**: one identifier per group (`level`, `rate`, `no_data`), so a new alert replaces the old; posts and
  removals are coalesced per run-loop turn (usernoted cancelled both adds when two same-id requests arrived ms apart).
  Sounds: the 84 `spikes/alerts/sounds/out/*.caf` are copied by `bundle.sh` into `Contents/Resources` —
  `UNNotificationSound(named:)` looks in the app container's `Library/Sounds`, a shared group container's
  `Library/Sounds`, then *"The main bundle of the current executable"*
  ([UNNotificationSound](https://developer.apple.com/documentation/usernotifications/unnotificationsound)). `system` =
  `.default`, `none` = silent. `swift run` (no bundle, no notification center) logs and plays the repo's `.caf`.
- **Time Sensitive**: needs the `com.apple.developer.usernotifications.time-sensitive` entitlement, which comes with a
  provisioning profile; `bundle.sh` signs without one, so lows go out `.active` (the app checks its own entitlement and
  switches to `.timeSensitive` once signed with it). Notification settings report `timeSensitive 0` (not supported).
- **Tests**: `swift run GlucoseBar -alerts-selftest` (engine over a scripted series with an injected clock, settings
  JSON, sounds; 53 checks), `-v2selftest` now includes alert events (accepted from a device, deduped across relay +
  BLE, forged / > 10 min old / wrong type rejected). On a launched app: `-alert-test <kind>` posts one test alert with
  its sound, `-alert-feed "200,1;260,1"` runs readings through this Mac's rules, `-alertsSnapshot out.png` renders the
  window; on the running app `Sukrara -debugsend '{"cmd":"alert-test","k":"low"}'` (also `alert-feed` with
  `"feed"`, `alert-state`, `alert-clear`, `alerts-window`).

## Code

| File | What |
|---|---|
| `NostrV2.swift` | `V2` helpers (H, gk, match code, invite parsing, gift wrap), `RelayPool` (reconnects, outbox, NIP-42 AUTH as F on `auth-required`), `FollowerV2` — the **one `onEvent(json, via:)`** every transport feeds; all acceptance rules; treatment writer |
| `BLELink.swift` | the one CoreBluetooth central: one connection per phone carrying both paths, serialised connects, silence watchdog |
| `BLEV2Central.swift` | v2 path on `BLELink` for the docs/07 GATT service (frames `[msgId][idx][count]`, HELLO/INFO/EVENT, one write in flight, scan by service UUID, connect timeout, silence watchdog, pause when the phone has nothing for us) |
| `FollowUI.swift` | follow dialog, QR-from-image, pairing window (match code) |
| `Keychain.swift` | follower state (F nsec, epoch read keys, sid, roster) as one login-Keychain item — never UserDefaults, never logged |
| `V2SelfTest.swift` | `GlucoseBar -v2selftest`: genuine vs. forged events through `onEvent` |
| `Migration.swift` | one-time GlucoseBar → Sukrara copy (defaults, Application Support, follower Keychain item) |
| `Alerts.swift` | alert settings (SPEC §3 JSON), `AlertEngine` (rules, mutes, repeats, injected clock), `Alerts` (notifications, sounds, wearer mirroring, test hooks) |
| `AlertsUI.swift` | the Alerts pane of the Settings window and its per-kind Details sheet |
| `SettingsUI.swift` | the Settings window (General, Graph, Alerts, Following; preference toolbar) and the app's main menu (⌘W, Edit) |
| `ReportWindow.swift` | the Glucose Report window (report 1/7/14/30 days, Treatments: last 7 days, delete) |
| `Panel.swift` | the glance panel under the menubar item (card, quick-log field + grammar, rows) |
| `AlertsSelfTest.swift` | `GlucoseBar -alerts-selftest` |
| `main.swift` | `Following` controller, wiring, debug commands (`follow-status`, `readings`, `unfollow`, `v2-treat`, `follower-pub`) |

Acceptance rules (same as Python/Android): key deliveries and "revoked" only if the **seal signer == I**; roster
only if authored by I (newest wins); live/chunks only if author ∈ roster `devices`, `gk` is a known epoch,
id + signature verify and NIP-44 decrypts to `v == 2`; unknown `gk` → rekey request (max 1 per 5 min per gk);
events are deduped by id across relays and BLE only after verifying (a forged copy can't shadow the real one) and
only once their gk is known (so a backlog that arrives before the key is re-read after it).

## Test results (2026-10-16, real public relays and the real phone radio)

**Nostr, against the Python v2 owner** (`v2/cli.py owner --every 30`, since retired (investigation 24, P15); separate
process and storage; the only shared thing is the relays):

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
- `cgmshare:` URL open (and the join page's *Open in Sukrara* button) not exercised live (registered in Info.plist); QR-from-image only via the self-test, not a
  photo of the phone's QR.
- **Keychain + ad-hoc signing**: the item has the default ACL (only the creating app reads it silently). Each
  rebuild of the ad-hoc-signed app is a new identity → macOS asks once ("Always Allow"). A shipped app would be
  Developer-ID signed and use the data-protection keychain with an access group.
- ~~No follower alarms~~ (alerts section above; test notifications delivered with their pack sound, see there;
  mirroring a real wearer's `alert` event not exercised until the phone publishes them). No local reading DB (the store keeps 3 h, every launch
  backfills 12 h from relays / ~25 h over BLE).
- Two GlucoseBar processes on one Mac can't both hold a BLE link to the phone (the second times out and retries;
  `BLELink` now tries `retrieveConnectedPeripherals` first, not tested with two processes).
- ~~Treatments: the Mac only sends~~ (receives + status since the "one Bluetooth link" section above). Edits: delete
  only (no edit UI).
- Test leftovers to clean up: on the phone, followers "Mac v2 BLE test", "Mac v2 BLE test 2", "Mac v2 writer test"
  (writer) and one note "GlucoseBar v2 writer test" in CGM Share's DB (Mac-side keys already deleted; removing them
  on the phone rotates the key). In the login Keychain, items `dev.spike.GlucoseBar.test1`, `.test6`, `.test6b`
  hold keys for the synthetic Python test owner only.
