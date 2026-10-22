# NovoPen Echo Plus → Sukrara: design contract (investigation 29)

The shared interface for the round `n-pen29`, written before dispatch. The Android agent (spike + app) and the Mac
agent both build against it. Change it only through the orchestrator.

## 1. What the user has and wants

- **NovoPen Echo Plus** (NFC, 0.5 u steps, stores ~800 doses with its own clock), rapid insulin. Used for a long time,
  scanned with a phone only a couple of times; it holds the full history. All of that insulin is **already logged
  by hand** (Sukrara treatments → LibreView, maybe older entries typed straight into LibreView, which Sukrara can't see).
- Tap the pen on the phone → the doses are logged, **without opening Sukrara** (works from any app; lock screen if
  Android allows it).
- **Priming** ("air shots" before an injection, 0.5 u ×1–3, 1 u, rarely 1.5 u, or combinations) is tracked separately,
  never counted as injected.
- Doses from the pen win over hand-typed entries for the same injection (pen time is exact). **Never a duplicate,
  never a lost dose, never the old pen history in the log.**

## 2. Reading (Android)

- Protocol: OpenNov (IEEE 11073-20601 PHD over NFC Forum Type 4), as in `third_party/xDrip/.../insulin/opennov`
  (with tests and recorded transcripts) and `third_party/Juggluco/.../NovoPen/opennov` (a copy). Port to **Kotlin**
  (GPL header, keep the original copyright lines). Port the xDrip tests and their transcripts.
- Background read: a manifest `TECH_DISCOVERED` filter with a tech list narrow enough to match the pen and **not**
  bank/transit cards or the Libre sensor (check what tech the pen really exposes; an NDEF + IsoDep combination is the
  first candidate), on a translucent, no-history, exclude-from-recents activity that reads on a background thread,
  vibrates, posts the result ("Pen read · 8.5 u logged") and finishes. The component is enabled only while pen reading
  is on (Settings). Sukrara's own reader mode (Sensor screen) must route a pen tap to the same reader. Research whether
  Android dispatches tags on the lock screen (Pixel, Android 16/17, "Require device unlock for NFC") and document it.
- Doses carry: pen serial, absolute time (computed from the pen's relative clock at scan time), units (0.1 u
  resolution), flags. **Dose key** = serial + the pen's raw relative time + units — stable across scans (the absolute
  time can move by seconds between scans).

## 3. Pen ledger (phone only, `pens.json`, never synced)

Per serial: insulin kind (`rapid` default, `long` selectable), `since` (epoch s), last read (time + pen clock sample),
every dose key seen and what it became (entry id + role, or `before-since`, `question`, `ignored`), the open questions.

- **First scan of a serial**: `since` = that scan time. Nothing older is ever imported automatically. The first scan
  opens a short setup in Sukrara (kind, what happened: "412 earlier doses in the pen weren't imported") — the only
  read that brings the app forward.
- Moving `since` earlier is possible from the pen's settings page, behind a preview (how many entries it would add,
  which hand-typed entries it would merge) and a warning that doses typed straight into LibreView can't be checked.
- Every dose key is processed once. Deleting a pen entry leaves its keys consumed (never re-imported). Rescanning the
  same pen changes nothing unless new doses appeared or a session grew.
- Pen clock sanity: drop doses later than scan time + 2 min (log it); if the pen clock went backwards (reset), match
  doses to known keys by units + time within 2 min and report instead of re-importing.

## 4. Turning doses into entries (pure Kotlin, scenario-tested)

Doses of one pen, sorted by time, below `since` dropped, 0 u dropped.

1. **Cluster**: consecutive doses ≤ 10 min apart.
2. A cluster with a dose ≥ **threshold** (setting `penPrimeBelow`, default 2.0 u) is an **injection**: the doses before
   the first ≥ threshold dose are priming, that dose and **every later one** in the cluster are injected.
   `1 u, 0.5 u, 8 u, 0.5 u` → injected 8.5 u, primed 1.5 u. Entry time `t` = time of the first injected dose.
3. A cluster with every dose below the threshold:
   - an injection cluster starts ≤ 30 min after it ends → it is that injection's priming;
   - else it starts ≤ 60 min after an injection cluster ended → a **top-up**: injected, added to that entry;
   - else → a **question** to the user ("What were 1 u and 1.5 u at 13:02?": priming / injected / some of each, per
     dose). Not logged until answered. If it's the newest cluster and < 30 min old at scan time it is **pending**
     (an injection may follow); it becomes a question 30 min after its last dose if no scan resolved it.
4. One entry per injection (`k` = pen kind), `a` = injected units, `pr` = primed units, `pd` = its doses (§5).
   A later scan that adds doses to an existing session updates that entry (same `id`, new `u`).
5. **Match hand-typed entries**: for each new injection, candidates = not-deleted entries of the same `k` without
   `pen`, not matched yet, `|t − T| ≤ 45 min`. One candidate with amount within max(1 u, 25 %) → **merge**: keep its
   `id`, `n` and `o`; set `t`, `a`, `pr`, `pen`, `pd`; new `u` (LibreView gets retraction + new version, followers
   update). Several candidates, or one outside the tolerance → a question ("Is this the 8 u you logged at 13:05?"
   Same dose / Different dose). Never a silent duplicate, never a silent drop.
6. **Reverse**: saving a hand-typed `rapid`/`long` entry within 45 min of a pen entry of that kind warns in the dialog
   ("Your pen already logged 8.5 u at 13:12") with *Log anyway* / *Cancel*.
7. User edits win: an entry the user edited after import isn't rewritten by later scans; doses that would join it
   become a question.

Every rule above is a scenario in a JSON fixture (doses + existing entries → entries + questions) run by the Android
unit tests, like `spikes/alerts` timeline scenarios. Include at least: the user's examples, top-up, priming then
distraction, two injections 40 min apart, all-small isolated, open session growing between two scans, pending → question,
first scan with 400 old doses, rescan idempotence, delete then rescan, manual entry merge, two candidates, amount
mismatch, edit then grow, clock reset, future dose, long pen, two pens.

## 5. Treatment format additions (SPEC §1, PROTOCOL-v2 content) — both platforms

Optional keys on insulin entries, absent on hand-typed ones:

| key | meaning |
|---|---|
| `pr` | primed units (1 decimal), not injected, never uploaded as insulin |
| `pen` | pen serial (string) the entry came from |
| `pd` | the doses: `[{"t":<epoch s>,"a":<u>,"p":<true if priming>}…]`, time order |

`a` stays the injected units, so every consumer that ignores the new keys stays right. Every implementation must
**keep** these keys through merge, storage, edits and re-publish (a Mac edit must not drop them). The Mac agent
updates `spikes/libreview/SPEC.md` §1, `lv.py` / `fixture.json` if they cover the shape, and `PROTOCOL-v2.md`.

Display, both platforms: one entry = one injection with the total (`8.5 u`); its detail shows the doses and the
priming (`1.5 u primed`) and that it came from the pen. Graph marks, insulin mutes, LibreView, Nightscout and
every other output use `a` only. Priming alone never makes an entry.

## 6. Surfaces (complete flows)

Android: pen reading switch, pen list (serial, kind, since, last read, counts; change kind; move `since`; forget
pen), threshold setting, questions (notification **and** an in-app card on Home/treatments; answer, change the answer
afterwards via the entry), the first-scan setup, the reverse-duplicate warning, a pen entry's detail. Mac: the pen
entry's detail and list row. Settings in `spikes/settings/settings.json` (`only: android` + reason). Strings en+es,
FEATURES.md (States and exits), README, QA.md, docs/10 + docs/29 "In Sukrara". Debug-only fake pen read through
`DebugFakes.run` (emulator screenshots of every state).
