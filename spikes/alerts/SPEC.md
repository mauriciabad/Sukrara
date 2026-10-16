# Glucose alerts — shared spec (investigation 15)

One rule set for **Sukrara for Android** (wearer + follower) and **Sukrara for Mac**. Report: [docs/15](../../docs/15-alerts.md).

## 1. Alert kinds

| `k` | Fires when (reading ≤ 15 min old) | Default on | Default threshold | Default re-alert | Sound mood |
|---|---|---|---|---|---|
| `urgent_low` | `sgv ≤ mgdl` | on | 55 mg/dL | 5 min | *game over*: longer (~3–4 s), unmistakable, not harsh |
| `low` | `sgv ≤ mgdl` (and not urgent low) | on | 65 mg/dL | 15 min | *lost a life*: short, descending |
| `high` | `sgv ≥ mgdl` (and not very high) | on | 185 mg/dL | 60 min | *final lap*: short "speed up" jingle (frequent → brief) |
| `very_high` | `sgv ≥ mgdl` | on | 250 mg/dL | 30 min | *boss incoming*: a bit longer than high |
| `falling_fast` | `rate ≤ −rate` | off | 2.0 mg/dL/min | 30 min | quick down sweep |
| `rising_fast` | `rate ≥ +rate` | off | 2.0 mg/dL/min | 30 min | quick up sweep |
| `no_data` | no reading for `min` minutes | on | 20 min | 60 min | *connection lost* blip |

- **Level alerts** (`urgent_low`, `low`, `high`, `very_high`): at most one is active, the most severe that matches.
  Moving to a more severe one fires at once (low → urgent low, high → very high).
- **Re-alert**: while the same kind stays active, it fires again every `repeatMin` minutes (0 = never repeat).
  Leaving the condition **clears** it (notification removed). Coming back to a kind within `repeatMin` of its last
  firing doesn't fire again (no flapping around a threshold).
- **Rate alerts** are independent of level alerts; `rate` is the reading's trend in mg/dL/min. No rate → no rate alert.
- `no_data` is checked by a timer (Android: `AlarmManager.setAndAllowWhileIdle`; Mac: 60 s timer), cleared by the next
  reading.

## 2. Mute rules (high side only)

Lows are never muted by these rules.

| Option | Default | Applies to | Rule |
|---|---|---|---|
| `muteAfterInsulin` | on, 20 min | `high`, `very_high`, `rising_fast` | A `rapid` treatment with `t` in `[now − min, now]` (not deleted) mutes them. When the window ends and it is still high, it fires then. |
| `muteFallingHigh` | on, 20 min | `high`, `very_high` | `rate < 0` and `sgv + rate × min < high.mgdl` (it will be back below the high threshold within `min` minutes at the current rate). Re-evaluated on every reading; a flattening or rising trend un-mutes at once. |

A muted alert is not "fired": no notification, no event to followers, and its re-alert timer doesn't start. The UI
shows why it's quiet ("High, muted: insulin 12 min ago").

## 3. Settings (JSON, same keys on both platforms)

```json
{
  "v": 1,
  "on": true,
  "alerts": {
    "urgent_low":   {"on": true,  "mgdl": 55,  "repeatMin": 5},
    "low":          {"on": true,  "mgdl": 65,  "repeatMin": 15},
    "high":         {"on": true,  "mgdl": 185, "repeatMin": 60},
    "very_high":    {"on": true,  "mgdl": 250, "repeatMin": 30},
    "falling_fast": {"on": false, "rate": 2.0, "repeatMin": 30},
    "rising_fast":  {"on": false, "rate": 2.0, "repeatMin": 30},
    "no_data":      {"on": true,  "min": 20,   "repeatMin": 60}
  },
  "muteAfterInsulin": {"on": true, "min": 20},
  "muteFallingHigh":  {"on": true, "min": 20},
  "sounds": {"pack": "arcade", "kinds": {"high": "kart"}, "shuffle": true},
  "source": "wearer"
}
```

- `sounds.pack`: the pack every kind uses; `sounds.kinds[k]` overrides one kind (mix and match); `"system"` = the
  platform's default notification sound, `"none"` = silent. `shuffle`: pick a random variant of the pack each time
  (off = always variant 1).
- `source` (followers only): `"wearer"` (default) = alert exactly when the wearer's app does (mirror its `alert`
  events, §4); `"own"` = run these rules locally on the followed readings and treatments. Settings are per device and
  never synced.
- In `"wearer"` mode a follower still checks **`no_data` locally** (it's about *its* link to the wearer, which the
  wearer can't report), with its own `no_data` settings.

## 4. `alert` event (wearer → followers)

New ephemeral event, same envelope as **live** (PROTOCOL-v2): kind **21079**, signed by `D`, `["gk", gk_n]`,
expiration now + 1 h, content NIP-44 `D → G_n`:

```json
{"v": 2, "type": "alert", "id": "<16 hex>", "k": "high", "state": "fire", "at": 1760650000,
 "t": 1760649990, "sgv": 192, "r": -0.4, "th": 185, "n": 2}
```

| Field | Meaning |
|---|---|
| `id` | random per firing; a `clear` repeats the `id` of the firing it ends |
| `k` | alert kind (§1) |
| `state` | `fire` (a notification was posted, including re-alerts) or `clear` (condition left: remove it) |
| `at` | when the wearer fired/cleared (unix s) |
| `t`, `sgv`, `r` | the reading that triggered it (`no_data`: the last reading, may be null) |
| `th` | the threshold that matched (mg/dL, mg/dL/min, or minutes for `no_data`) |
| `n` | 1 for the first firing, 2… for re-alerts |

- Published on **relays** (Lane LIVE, follows the "Nostr" switch, ignores the batching interval: alerts are always
  sent at once) and over **BLE** to subscribed followers (backlog key `alert`, so a follower that connects within
  minutes still gets the newest one).
- Followers accept it with the **same checks as live** (author ∈ `roster.devices`, known `gk`, decrypts, `v == 2`),
  dedupe by event id (it can arrive over BLE and several relays), and **ignore `at` older than 10 min**.
- In `"wearer"` mode the follower posts the same kind with its **own** sound choice; `clear` removes it.
  In `"own"` mode `alert` events are ignored.
- Followers subscribe with `{"kinds":[21078,21079], "authors": roster.devices, "since": now − 120}`.

## 5. Sound packs

`spikes/alerts/sounds/` generates every pack from code (`gen.py`, original melodies, no samples). Files:
`<pack>_<kind>_<n>.ogg` (Android, `res/raw`) and `.caf` (Mac, IMA4, copied into `Sukrara.app/Contents/Resources` by
`bundle.sh`: `UNNotificationSound(named:)` looks in the main bundle). Every pack has the 7 kinds × 3 variants. Packs:
`arcade` (8-bit), `kart` (racing), `quest` (adventure / RPG), `soft` (calm, non-game).

## 6. Platform notes (details and sources: docs/15)

| | Android | Mac |
|---|---|---|
| Per-kind sound | one notification channel per kind; its sound is `content://<pkg>.alertsound/<kind>`, an exported provider that picks the pack + variant **each time SystemUI opens it** | `UNNotificationSound(named:)` per notification |
| User override | channel settings (sound, vibration, DND) win over the app | none (macOS only has "Play sound for notification" on/off) |
| Category | lows `CATEGORY_ALARM` + `USAGE_ALARM` (alarm volume, "Alarms only" DND, no cooldown); others `CATEGORY_REMINDER` (no post-unlock cooldown, DND "Reminders") | lows `.timeSensitive` only with the entitlement, else `.active` |
| Haptics | a primitives-only `VibrationEffect` per channel (falls for lows, speeding-up clicks for high…); an on/off waveform where primitives aren't supported. Haptic channels inside the sound file are muted for notifications | — |
