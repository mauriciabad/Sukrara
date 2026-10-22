# NovoPen in Sukrara: product spec (investigation 29, round `n-pen29`)

The user-facing half of [DESIGN.md](DESIGN.md). DESIGN.md says what the data does; this file says what the user sees,
says and taps, in every situation, with the exact copy (en + es). Where the two disagree, this file's §3 rules win
(each change is marked **Changed** with its reason) once the orchestrator folds them into DESIGN.md.

Scope rule: fewer, well-chosen features. One setting (the priming size), one history page, one correction sheet.

## 0. Research (what others do, what the pen's makers say)

| Source | What it says | What we take |
|---|---|---|
| Novo Nordisk, NovoPen Echo Plus user guide ([PDF](https://www.novonordisk.com/content/dam/nncorp/global/en/our-products/pdf/instructions-for-use/novopen-echo-plus/Novopen-echoplus-EN_8-4261-00-006-1.pdf)), quick guide NovoPen 6 / Echo Plus ([PDF](https://www.novonordisk.com/content/dam/nncorp/global/en/our-products/pdf/novopen-echo-and-6/NP6E-quick-guide-June-2021.pdf)), UK guide ([emc](https://www.medicines.org.uk/emc/rmm/2744/Document)) | "Test the insulin flow" before **every** injection: dial **2 units**, press, repeat until insulin squirts. After a new cartridge or a twisted-apart pen it may take **several** tries. A blocked needle → new needle, test again. The display shows only the **last** press (injection or flow test) and the hours since. If the dose is more than the cartridge holds, the guide says to take the full dose from a new cartridge (people split it anyway). | Priming comes in runs, may be repeated, may be 2 u for people who follow the guide (so the size is a setting, default 2 u for this user's 0.5–1.5 u habit). Priming can sit **between** two parts of a split dose (cartridge change). The display's "last dose + hours" is a check the user can do with their own eyes (§6, untested pens). |
| Novo's own term | "Flow check" / "insulin flow test" / "airshot" (UK forums). | We say **priming** (InPen's word, and the user's). Spanish: **purgado** / *purgar la pluma*, the everyday term in Spain. |
| FreeStyle LibreLink + NovoPen (forum reports, [diabetes.co.uk 1](https://www.diabetes.co.uk/forum/threads/smart-insulin-pens-how-do-i-avoid-the-priming-dose-being-included-in-the-actual-dosing-injection.192147/), [2](https://www.diabetes.co.uk/forum/threads/freestyle-app-and-novopens.198329/)) | Every press is imported, airshots included; users fix each one by hand in the logbook (a drop-down per dose) or the airshot ends up in LibreView totals. Users praise Juggluco for "only capturing the real dose". | Automatic split is the point; correction is the exception, but must exist. |
| Medtronic InPen ([IFU](https://www.medtronicdiabetes.com/sites/default/files/library/download-library/user-guides/InPen-user-guide.pdf), [FAQ](https://www.minimed.com/en-us/support/product-support/inpen/faq)) | Primes are detected when a dose follows soon after; a prime too long before its dose shows as a dose and the user relabels it in the Logbook: tap the entry → **Dose / Prime** selector. Primes never count in active insulin or reports. | Two-way per-dose selector on tap of the entry. Priming never in totals or outputs. |
| xDrip+ (`third_party/xDrip/.../insulin/opennov/data/SaveCompleted.java`, `insulin/shared/PrimeDetection.java`) | Opt-in "remove / hide priming": a dose ≤ N units followed by a real dose within M minutes is priming; orphan primes ignored by default (InPen). Three knobs. | Same idea, no knobs but the size. Orphans are **asked**, never silently dropped (the user: all-small → ask). |
| Juggluco (`third_party/Juggluco/.../settings/javasettings.cpp`, `novopenvalue`) | A dose ≤ 2.0 u less than 60 s before the next saved dose is a prime; only one prime per dose; no correction UI. | Confirms 2 u as the common cut; 60 s is too short for "prime, then distraction". |
| Glooko, mySugr, Tidepool | They list pen doses as boluses; we found no documented priming detection (only retailer pages). | Nothing to copy. |
| Android NFC ([tag dispatch](https://developer.android.com/develop/connectivity/nfc/nfc), Android 16 `NfcAdapter.isTagIntentAllowed` / `ACTION_CHANGE_TAG_INTENT_PREFERENCE`); lock-screen reports ([GrapheneOS #3438](https://github.com/GrapheneOS/os-issue-tracker/issues/3438), Samsung community) | Tags are dispatched only while the phone is **unlocked** (Pixel; "Require device unlock for NFC" doesn't change it for tags). Android 16+ lets the user block an app's tag intents. | Copy says "unlock your phone, then hold the pen to it". The engineer confirms on the Pixel (DESIGN §2); if locked reads turn out to work, §4.1 already fits the lock screen. Blocked tag intents get the sensor page's row. |
| Material 3 ([snackbar](https://m3.material.io/components/snackbar/guidelines), [bottom sheets](https://m3.material.io/components/bottom-sheets/guidelines), [segmented buttons](https://m3.material.io/components/segmented-buttons/guidelines), [dialogs](https://m3.material.io/components/dialogs/guidelines)); Android [notifications](https://developer.android.com/develop/ui/views/notifications) and [channels](https://developer.android.com/develop/ui/views/notifications/channels); [accessibility timeouts](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager#getRecommendedTimeoutMillis(int,int)) | Snackbar: one action, short, in-app only. Don't post a notification for something the user is looking at. Dialogs only for decisions that block. Segmented buttons for 2–5 exclusive options. Timed UI must respect the user's accessibility timeout. | Tap feedback is the read sheet itself (no success notification); notifications only for what waits for the user (questions); undo is a pill on the sheet + a snackbar in the app. |

## 1. User goals

1. **Tap and forget**: unlock, hold the pen to the phone, feel the vibration, see "8.5u logged", put the phone away.
   No app opening, no typing, from any app.
2. **The log tells the truth**: injected insulin only (priming never counted, but kept, because it explains insulin
   use per cartridge and catches a wrong split), the pen's exact time, every injection exactly once.
3. **History stays as it is**: years in the pen never flood the log; what they typed by hand (and the doctor already
   sees in LibreView) is upgraded, never doubled.
4. **Rarely asked, quickly answered**: only genuinely unclear doses ask, with the likely answer one tap away.
5. **Every automatic decision is visible and reversible**: what was logged, what was merged, what was priming.

## 2. Terminology (UI words; code names in brackets)

| Concept | en | es | Notes |
|---|---|---|---|
| the device | pen, *your pen* | pluma, *tu pluma* | "pluma de insulina" in Spain (Novo ES). Model names stay as is (`translatable=false`). |
| one press of the button (pen record) [dose] | dose | dosis | |
| doses ≤ 10 min apart [session / cluster] | — (never shown) | — | The user sees an injection, not a session. |
| a dose that went into the air [priming] | priming · *1.5u primed* | purgado · *1.5u purgadas* | Never "air shot", "flow check", "prime dose". |
| a dose that went into the body | injected | inyectada | Segment labels: *Priming* / *Injected*; es *Purga* / *Inyección* (nouns, no gender clash). |
| one logged entry from the pen | injection; row = *8.5u rapid · from the pen* | inyección; *8.5u rápida · de la pluma* | Reuses `treatments_row_from`. |
| an unclear dose waiting for the user [question] | *Priming or injected?* | *¿Purgado o inyección?* | Never "question", "conflict", "error". |
| pen dose + hand entry made one [merge] | *replaced your 8u entry* | *sustituye tu entrada de 8u* | |
| doses before the first scan [before-since] | *earlier doses* / *Before Sukrara* | *dosis anteriores* / *Antes de Sukrara* | |
| the history page | *Doses read* | *Dosis leídas* | |
| amounts | `8.5u`, `0.5u` | `8.5u` | App rule: dot, lowercase `u`, no space (`TreatmentMarks.number`, Locale.ROOT). TalkBack says "8.5 units" / "8,5 unidades". |

## 3. Dose rules (what I endorse, what I change)

DESIGN §4 numbers in brackets. "Small" = below the priming size (setting, default **2u**); "large" = the rest.

1. **[§4.1] Session = doses ≤ 10 min apart.** Endorsed.
2. **[§4.2] Changed: small doses *before* a large dose are priming; small doses *after the last* large dose are
   injected (a top-up, summed into the entry).** DESIGN made everything after the first large dose injected. Reason:
   the split dose around a cartridge change, `5, 0.5, 0.5, 1, 3` (5u from the old cartridge, three flow checks on the
   new one, 3u more) is 8u injected / 2u primed; DESIGN's rule logs 10u. The user's own examples still hold:
   `1, 0.5, 8, 0.5` → 8.5u injected, 1.5u primed; `1, 8` → 8u, 1u primed. Entry time = first injected dose. In words
   (shown on the settings page): *Smaller doses before a bigger one are priming; after it, they're added to it.*
3. **[§4.3a] A small-only session ending ≤ 30 min before an injection session is that injection's priming.**
   Endorsed (prime, phone rings, inject 15 min later).
4. **[§4.3b] Changed: no automatic top-up across sessions.** DESIGN added a small-only session ≤ 60 min after an
   injection to that injection. Reason: a 1u correction 45 min after a meal bolus is a separate injection; summing it
   moves insulin 45 min earlier in LibreView and in the graph, the one thing the user wants exact. A top-up *right
   after* (≤ 10 min) is already in the session (rule 2). So every small-only session that isn't priming becomes
   **Priming or injected?** — with the likely answer pre-selected (rule 5).
5. **[§4.3c] Unclear doses ask, with a suggestion.** Suggested split: the **last** dose injected, the ones before it
   priming (`1, 1.5` → 1.5u injected at its time, 1u primed; a lone `1` → 1u injected). One tap accepts it
   (notification action, card pill, sheet). Pending for 30 min while the newest session might still get its injection
   (DESIGN, endorsed). Never logged until answered; never dropped.
6. **[§4.5] Merging hand-typed entries**: endorsed (same kind, ±45 min, amount within max(1u, 25 %); several or a
   mismatch → **Same injection?**). Added: every merge is **said** on the read sheet (*Replaced your 8u entry at
   13:05*) and undoable; the merged entry keeps the note.
7. **[§4.6] Typing after the pen logged it** → *Already logged by your pen?* on Save (Android dialog, Quick treatment,
   **and the Mac's add dialog** (added: the Mac can log too)).
8. **[§4.7] Corrections win** over later scans. Endorsed.
9. **[§3] First scan: nothing older is logged automatically.** Endorsed, plus **one offer**: if the pen's newest
   injection is under 24 h old, the setup shows it with *Log it* (people tap a new pen right after injecting and
   expect that dose). It goes through rule 6, so a hand-typed copy is merged, not doubled.
10. **[§3] Changed: no movable start date.** Replaced (as already sent to the engineer) by **Doses read**: every
    session the pen holds, each with its status; an earlier injection is logged one at a time with *Log this
    injection* (merge preview included). Reason: one place, no date picker, no bulk import of years.
11. **New: a big read is flagged, not held.** A read that logs an injection **older than 72 h** (pen not scanned
    for days, reading turned back on, new phone) logs like any read (rules 1–10, merges included) and the card says
    *23 injections logged since 2 Oct* with *Review* (§4.9) and *Undo*. Reason: the user (2026-10-23): a pen read is
    never confirmed and nothing blocks; the flood risk is the pen's pre-Sukrara history, which rule 9 already keeps out.
17. **Nothing blocks a read** (the user, 2026-10-23: "the user doesn't have to confirm the pen reading … it cannot be
    something blocking"). Every read logs what the rules decide at once and reports it in a card that closes by itself
    plus a quiet notification. The only things that wait are the doses the rules can't decide (rule 5, the user's own
    "ask" case; they are *not* logged meanwhile, everything else in the same read is) and the one-time untested-pen
    check (§6). Even the first scan of a new pen doesn't open the app (§4.7).
12. **[§4.4] Priming alone never makes an entry**; it stays visible in Doses read (*Only priming*).
13. **Pen flags**: a dose the pen marks incomplete counts with the units the pen reports as delivered and shows
    *Incomplete* in the dose list; it never asks. A partial or failed read logs **nothing** (*Couldn't read the pen*).
14. **Clock** [§3]: future doses are ignored (Doses read: *Ignored: time in the future*); a reset pen clock logs
    nothing new on its own: the sheet says *Pen clock changed* → Doses read.
15. **The priming size** (`penPrimeBelow`): one number setting, 0.5–5u in 0.5 steps, presets 1.5 / 2 / 2.5 / 3,
    default 2 (schema `only: android`). Kept because Novo's own guide teaches 2u flow checks: a user who follows it
    needs 2.5. Changing it applies to doses read from then on (never re-splits logged entries).
16. **Untested pens** (§6): nothing is logged from a pen model other than the NovoPen Echo Plus until the user has
    compared one read with the pen's display.

## 4. Surfaces (Android)

Visual language (NEXT.md): actions are pills, red only for errors, no helper text inside dialogs, no pointless
details, AOSP-style settings, every string en + es.

### 4.1 Read sheet (the tap, from any app)

Translucent `PenReadActivity` (no history, not in recents) shows a **floating card** at the bottom (16 dp side
margins, 28 dp corners, surfaceContainerHigh, no scrim; the app behind stays visible). Tap outside, swipe down or back
closes it. It closes by itself after `getRecommendedTimeoutMillis(6000, CONTENT_TEXT|CONTENT_CONTROLS)` (never while
TalkBack focus is inside). Live region (polite) announces the title. Haptics: `CONFIRM` on success, `REJECT` on failure
(the sensor scan's pair).

```
┌───────────────────────────────────────────┐
│ (pen glyph in wavy ring)  Reading your pen…│      reading
│                            Keep it still.  │
└───────────────────────────────────────────┘
┌───────────────────────────────────────────┐
│ ✓  8.5u logged                             │      logged (one injection)
│    Rapid · 13:12 · 1.5u primed             │
│    Replaced your 8u entry at 13:05         │      (only when merged)
│                       ( Undo )  ( Open )   │
└───────────────────────────────────────────┘
```

| State | Title (en / es) | Line(s) | Pills |
|---|---|---|---|
| reading | *Reading your pen…* / *Leyendo la pluma…* | *Keep it still.* / *No la muevas.* | — |
| logged, 1 | *8.5u logged* / *8.5u registradas* | *Rapid · 13:12 · 1.5u primed* / *Rápida · 13:12 · 1.5u purgadas* (no priming part when 0); + merge line *Replaced your 8u entry at 13:05* / *Sustituye tu entrada de 8u de las 13:05* | *Add treatment* / *Añadir tratamiento*, *Undo* / *Deshacer*; the card body opens the entry (§4.3) |
| logged, several | *3 injections logged* / *3 inyecciones registradas* | *14.5u since yesterday 21:40* / *14.5u desde ayer 21:40*; + *2 replaced your entries* / *2 sustituyen tus entradas* | *Add treatment*, *Undo* |
| entry grew (top-up after an earlier scan) | *Now 8.5u* / *Ahora 8.5u* | *Rapid · 13:12 · 0.5u added* / *Rápida · 13:12 · 0.5u añadidas* | *Add treatment*, *Undo* |
| nothing new | *Nothing new* / *Nada nuevo* | *Last injection: 8.5u at 13:12* / *Última inyección: 8.5u a las 13:12* | — (closes after 3 s) |
| only priming so far (pending) | *Only priming so far* / *Por ahora solo purgado* | *Hold the pen to the phone again after you inject.* / *Vuelve a acercar la pluma después de inyectarte.* | — |
| unclear | *Priming or injected?* / *¿Purgado o inyección?* | *1u and 1.5u at 13:02* / *1u y 1.5u a las 13:02* | *Log 1.5u* / *Registrar 1.5u* (the suggestion), *Check* / *Revisar* (opens §4.4) |
| same injection? | *Same injection?* / *¿Es la misma inyección?* | *Pen 8.5u at 13:12 · yours 5u at 13:05* / *Pluma 8.5u a las 13:12 · tuya 5u a las 13:05* | *Check* / *Revisar* (opens §4.5) |
| big read (rule 11) | *23 injections logged* / *23 inyecciones registradas* | *Since 2 Oct · 18 replaced your entries* / *Desde el 2 oct · 18 sustituyen tus entradas* | *Review* / *Revisar* (§4.9), *Undo* |
| clock changed | *Pen clock changed* / *El reloj de la pluma ha cambiado* | *Nothing new was logged. Check its doses.* / *No se ha registrado nada nuevo. Revisa sus dosis.* | *Check* / *Revisar* (Doses read) |
| failed / partial | *Couldn't read the pen* / *No se ha podido leer la pluma* (error container, the only red) | *Nothing was logged. Hold it to the phone until it vibrates.* / *No se ha registrado nada. Mantenla junto al teléfono hasta que vibre.* | — (tapping again retries) |
| pen not usable (untested, failed check) | *Sukrara doesn't log this pen* / *Sukrara no registra esta pluma* | *Its doses didn't match its display.* / *Sus dosis no coincidían con su pantalla.* | *Open* |
| new pen | *New pen added* / *Pluma nueva añadida* | *NovoPen Echo Plus · rapid · 412 earlier doses left out* / *NovoPen Echo Plus · rápida · 412 dosis anteriores fuera* | *Log 8.5u* / *Registrar 8.5u* (its newest injection, only if < 24 h old; rule 9 merge applies, the line then says *Matches your 8u entry*), *Set up* / *Configurar* (§4.7) |

Several states at once: logged + unclear → the logged card with the unclear line under it and both pill sets
(*Undo*, *Check*). *Undo* removes the entries this read created and puts merged entries back as they were (a new
version, so LibreView and followers follow); the sheet then says *Undone* / *Deshecho*, *Nothing from this read is
logged.* / *No queda nada registrado de esta lectura.* and closes. Undone sessions show *Not logged* in Doses read,
where *Log this injection* brings them back. Tapping the card's text opens Sukrara's home with the entry's sheet
(§4.3). At most two pills per card (*Check* takes *Add treatment*'s place when something waits).

**Add treatment** (the user, 2026-10-23: after lunch they inject rapid and long, and want to add the other treatments
from where they see the pen read): opens the existing Quick treatment dialog (`QuickTreatmentActivity`, over the app
the user is in) with *When?* set to the pen injection's time (*Today 13:12*), focus on the field the pen didn't fill:
**Long** after a rapid read (their after-lunch case; carbs one tap away), **Rapid** after a long read. A second pen
of the other kind makes this unnecessary: tapping it too gives its own card. The dialog's duplicate warning (§4.6) still
applies, so re-typing the pen's dose is caught. Offered on the read card, the read notification, the pen entry sheet
(§4.3) and Doses read's session sheets.

**Read notification** (non-blocking, so the result is still there after the card closed or the phone was put down):
channel *Pen reads* / *Lecturas de la pluma*, **low importance** (no sound, no vibration, no heads-up: the card and
the haptic already told the user), title = the card's title (*8.5u logged*), text = its line, actions *Add treatment*
and *Undo*; tap = the entry's sheet. Each read replaces the previous one; it times out after 2 h. Swiping it away
changes nothing. A second channel *Pen doses to check* / *Dosis de la pluma por revisar* (default importance, silent)
carries only what waits for the user (§4.4, §4.5) and stays until answered.

### 4.2 Home: treatments list row

`13:12  8.5u rapid · from the pen   12m  ☁` (es `8.5u rápida · de la pluma`), with a note `8.5u rapid · Pizza ·
from the pen`. Priming is not in the row (it is in the sheet). Tapping a pen row opens §4.3 (hand rows keep today's
delete dialog). TalkBack: "13:12, 8.5 units rapid, from the pen, 12 minutes ago, in LibreView".

### 4.3 Pen entry sheet (see + correct + delete)

M3 modal bottom sheet. Entry points: home row, the read sheet's *Open*, a Doses read session, the merge / unclear
flows after answering. Header total updates live while correcting.

```
 8.5u rapid                                   13:12
 From your pen · 1.5u primed
 ─────────────────────────────────────────────────
 13:10   1u      [ Priming ✓ |  Injected  ]
 13:11   0.5u    [ Priming ✓ |  Injected  ]
 13:12   8u      [  Priming  | Injected ✓ ]
 13:12   0.5u    [  Priming  | Injected ✓ ]
 ─────────────────────────────────────────────────
 Pizza                                      (note, if any)
 Replaced your 8u entry at 13:05            (if merged)
 ( Delete )        ( Add treatment )       ( Done )
```

- Strings: *From your pen* / *De tu pluma*; with two pens *From <pen name>* / *De <nombre>*; *1.5u primed* /
  *1.5u purgadas*; *Incomplete* tag on such a dose / *Incompleta*; untested model: the red *Untested* tag after the
  pen line.
- Correcting: each dose has an M3 segmented button *Priming* / *Injected* (es *Purga* / *Inyección*). A change shows
  *Cancel* / *Save* instead of *Delete* / *Done*. *Save* recomputes `a`, `pr`, `t` (SPEC §1), closes, and home shows
  the snackbar *Entry changed* / *Entrada cambiada* · *Undo*.
- Every dose set to priming: the header reads *Nothing injected* / *Nada inyectado* · *2.5u primed*, and *Save* becomes
  *Remove from log* / *Quitar del registro* (plain, not red); after it: snackbar *Removed from your log* / *Quitado del
  registro* · *Undo*. The doses stay in Doses read as *Only priming*.
- *Delete* → today's dialog (*Delete this entry?* …). Deleted doses stay read (never re-imported) and show *Deleted*
  in Doses read, from where *Log this injection* restores them.
- TalkBack: each dose row is one node "13:10, 1 unit, priming", with custom actions *Mark as injected* / *Marcar como
  inyectada* and *Mark as priming* / *Marcar como purgado*.
- Followers see the same sheet read-only (no segmented buttons; `writer` scope can't change the wearer's entries).

### 4.4 Priming or injected? (unclear doses)

Three ways in, same sheet: the read sheet's *Check*, the home card, the notification. The home card sits **above the
glucose card** (it waits for the user; like the backup card, surface container, not red):

```
 ┌──────────────────────────────────────────────┐
 │ Priming or injected?                          │
 │ 1u and 1.5u at 13:02                          │
 │  ( Log 1.5u )   ( Check )                     │
 └──────────────────────────────────────────────┘
```

Several waiting: *3 pen doses to check* / *3 dosis de la pluma por revisar* · *Check* (opens them one after the other).
Notification: title *Priming or injected?*, text *1u and 1.5u at 13:02 from your pen*, actions *Log 1.5u* (the
suggestion) and *All priming* / *Todo purgado*; tapping opens the sheet. Answering anywhere clears the other two.

Sheet: title *Priming or injected?*, the doses with the same segmented buttons as §4.3, pre-set to the suggestion, the
result line *Logs 1.5u at 13:03 · 1u primed* / *Registra 1.5u a las 13:03 · 1u purgadas* (or *Nothing to log · 2.5u
primed* / *Nada que registrar · 2.5u purgadas*), buttons *Not now* / *Ahora no* and *Save* / *Guardar*. *Not now*
keeps the card and notification. There is no "ignore": all priming *is* the "not injected" answer. An answer is
changed later from the entry (§4.3) or Doses read.

### 4.5 Same injection? (merge candidates)

Sheet: title *Same injection?* / *¿Es la misma inyección?*; two rows *Pen · 8.5u at 13:12* / *Pluma · 8.5u a las
13:12* and *Logged by you · 5u at 13:05* (or *by Ana* / *on the Mac*: es *Registrada por ti* / *por Ana* / *en el
Mac*); buttons *Different injections* / *Inyecciones distintas* (keeps both) and *Same injection* / *La misma* (the
pen's replaces it, the note kept). Several candidates: one radio row per candidate plus *None of these* / *Ninguna*,
button *Save*. Same ways in as §4.4 (card *Same injection?* · *Pen 8.5u at 13:12* · *Check*).

### 4.6 Already logged by your pen? (typing after a read)

On *Save* in the treatment dialog / Quick treatment, when a rapid (long) amount is saved within 45 min of a pen entry
of that kind: AlertDialog title *Already logged by your pen?* / *¿Ya lo registró tu pluma?*, text *8.5u rapid at
13:12* / *8.5u rápida a las 13:12*, buttons *Cancel* / *Cancelar* (back to the dialog, values kept) and *Log anyway*
/ *Registrar igualmente*. Only the insulin part is held; carbs and note in the same save are saved either way.

### 4.6b Reading the pen into the treatment dialog

The user, 2026-10-23: the Add treatment dialog shows a minimal sign that the pen can be read there, and a tap fills
the dialog; **here a confirmation is right**, because the dialog already had *Save* from the start.

- While the dialog is open (home, Quick treatment / widget / tile / notification) and pen reading is on with NFC on,
  the dialog's activity holds NFC reader mode for pens. The sign: a 20 dp contactless-pen icon (`ic_dia_pen_nfc`,
  onSurfaceVariant) at the end of the title row, before the *When?* chip. No text. TalkBack: *Hold your pen to the
  phone to fill this in* / *Acerca tu pluma al teléfono para rellenarlo*. Hidden when reading is off, NFC is off or
  blocked, or on a follower.
- A tap reads the pen (the icon spins as the wavy ring; `CONFIRM` / `REJECT` haptics) and takes the **newest
  not-yet-logged injection**: its injected units go into **Rapid** or **Long** by the pen's kind (the field gets the
  pen colour mark and the supporting line *From the pen · 1.5u primed* / *De la pluma · 1.5u purgadas*), *When?* is
  set to its time (*Today 13:12*); carbs and note typed in the same dialog share that time. Nothing is logged yet.
- *Save* logs it as a pen entry (rules 2–8: priming, merge with a hand entry, all as on a background read; a merge
  shows on home as the usual snackbar *Replaced your 8u entry at 13:05* · *Undo*). *Cancel* logs nothing from the pen:
  its doses stay unread, so the next tap anywhere logs them as usual.
- Unclear doses (rule 5) fill the suggestion and the supporting line reads *1u primed · tap to change* / *1u purgadas ·
  toca para cambiar*; tapping it opens §4.3's dose list with the segmented buttons inside the dialog. Saving is the
  answer.
- Typing over a pen-filled amount turns it back into a hand-typed value (mark and line go away); the pen's doses are
  then matched on the next read as usual (rule 6).
- Other new injections in the same read (older than the newest) are logged as on a background read; the dialog shows
  one line under the fields: *Also logged: 2 earlier injections* / *También registradas: 2 inyecciones anteriores*.
- Nothing new: the icon area shows *Nothing new* / *Nada nuevo* for 3 s; failure: *Couldn't read the pen* in the
  error colour for 3 s; the fields are untouched either way. A new pen read here is added (rapid) and fills the
  dialog with its newest injection if < 24 h old (rule 9).
- The Mac has no NFC: nothing changes there.

### 4.7 New pen (first scan)

Full-screen, the New sensor screen's shape (close button, illustration, title, content, action at the bottom; no
step bar: one step). Opens from the new-pen card's *Set up* (and the pen page), **not** by itself: the first tap
already added the pen as rapid (§4.1 *New pen added*), so the user can ignore the setup entirely.

```
 ✕                                   
           [ pen illustration ]
   NovoPen Echo Plus             (NovoPen 6  [Untested])
   Serial FHB2N9KE

   Insulin in this pen
   (•) Rapid      ( ) Long

   412 earlier doses stay out of your log.
   ┌────────────────────────────────────────┐
   │ Last injection: 8.5u at 13:12 (4m ago)  │
   │ Matches your 8u entry at 13:10          │   (if a candidate)
   │                          ( Log it )     │
   └────────────────────────────────────────┘
                                   ( Done )
```

- Title *New pen* / *Nueva pluma*; *Insulin in this pen* / *Insulina de esta pluma* · *Rapid* / *Rápida*, *Long* /
  *Lenta*; *412 earlier doses stay out of your log.* / *Las 412 dosis anteriores no entran en tu registro.* (0 → line
  hidden); *Last injection: 8.5u at 13:12 (4m ago)* / *Última inyección: 8.5u a las 13:12 (hace 4m)*; *Matches your
  8u entry at 13:10* / *Coincide con tu entrada de 8u de las 13:10*; *Log it* / *Registrarla*, after it *Logged* /
  *Registrada* (tick, pill gone). *Done* / *Listo* saves the kind and starts the pen; ✕ / back = the same with Rapid
  (the pen is known either way: no second setup next tap). Earlier doses are in Doses read (*Before Sukrara*).
- **Returning pen** (serial seen in synced entries: new phone, reinstall): the same screen and title, kind pre-set from those entries, the earlier line reads *Already in your log up to yesterday
  21:40. Newer doses are logged now.* / *Ya está en tu registro hasta ayer 21:40. Las dosis más nuevas se registran
  ahora.* Rule 11 still applies to the newer ones (review if older than 72 h).
- **Second pen**: the same screen; its default name becomes the model plus the serial's last 4 when two pens share a
  model (*NovoPen Echo Plus ·9KE*). Renaming is on the pen page.

### 4.8 Settings → Insulin pens

Overview row (wearer only), after *Sensor end*, icon `ic_dia_pen` (custom, Material Symbols grid): *Insulin pens* /
*Plumas de insulina*, summary *Off* / *NovoPen Echo Plus · read 13:14* / *2 pens · read 13:14* / *Blocked in Android
settings*. Searchable (every row below).

Page:
- Intro (TopIntroPreference): *Unlock your phone and hold the pen's end to the back of it. Sukrara logs the doses
  without opening.* / *Desbloquea el teléfono y acerca el extremo de la pluma a su parte trasera. Sukrara registra
  las dosis sin abrirse.*
- MainSwitchPreference *Read insulin pens* / *Leer plumas de insulina* (off by default; on enables the NFC component).
- Status rows (only when they apply, the source page's pattern): *NFC is off* · *Turn on NFC* (system NFC settings);
  *Blocked in Android settings · tap to allow* (Android 16+, `ACTION_CHANGE_TAG_INTENT_PREFERENCE`; reuse
  `sensor_launch_on_scan_blocked`); *Juggluco also reads pens* / *Juggluco también lee plumas* · *Turn off its NovoPen
  reading so the pen opens Sukrara* (shown when another installed app matches the pen's tech filter; tap = that app's
  App info).
- Group *Your pens* / *Tus plumas*: one row per pen: name (+ red *Untested* tag), summary *Rapid · read 13:14* /
  *Rápida · leída 13:14*, opens the pen page. Empty: *Hold your pen to the phone to add it.* / *Acerca tu pluma al
  teléfono para añadirla.*
- Group *Priming* / *Purgado*: number row *Priming is smaller than* / *Purgado: menos de* · *2u* (NumberDialog,
  §3.15); footer *Smaller doses before a bigger one are priming; after it, they're added to it.* / *Las dosis más
  pequeñas antes de una mayor son purgado; después, se suman a ella.*

Pen page (AOSP device-details shape, like Glucose source): header card *NovoPen Echo Plus* (+ *Untested*), *Serial
FHB2N9KE*, *Started 21 Oct* / *Desde el 21 oct*, *Last read 13:14* / *Última lectura 13:14*. Rows: *Name* / *Nombre*
(text dialog), *Insulin* / *Insulina* (Rapid / Long, ListPreference; applies to doses read from now on), *Doses read*
/ *Dosis leídas* › (§4.9), untested: *Send feedback* / *Enviar comentarios* (pre-filled issue, label
`pen-feedback`, model + versions only), *Forget this pen* / *Olvidar esta pluma* → dialog *Forget this pen?* /
*¿Olvidar esta pluma?*, text *Its entries stay in your log.* / *Sus entradas se quedan en tu registro.*, *Forget* /
*Olvidar*, *Cancel*. A forgotten pen tapped again is a returning pen (§4.7).

### 4.9 Doses read (history, corrections, earlier doses, big-read review)

Settings page with the pen's sessions, newest first, grouped by day (*Today*, *Yesterday*, dates). One row per
session: time, `8.5u` big, summary with the status:

| Status | Summary (en / es) | Tap |
|---|---|---|
| logged | *Logged · 1.5u primed* / *Registrada · 1.5u purgadas* | §4.3 |
| merged | *Replaced your 8u entry* / *Sustituyó tu entrada de 8u* | §4.3 |
| corrected | *Logged · changed by you* / *Registrada · cambiada por ti* | §4.3 |
| only priming | *Only priming · 1.5u* / *Solo purgado · 1.5u* | sheet with *Log as injection*: segmented buttons, *Save* |
| waiting | *Priming or injected?* / *Same injection?* | §4.4 / §4.5 |
| not logged (undone, deleted, removed) | *Not logged* / *No registrada* | sheet with *Log this injection* / *Registrar esta inyección* |
| before Sukrara | (folded: a pill *412 earlier doses* / *412 dosis anteriores*, SettingsLib count pill; *See less* folds) → rows *Before Sukrara* / *Antes de Sukrara* | sheet with *Log this injection* (shows *Matches your 8u entry at 13:10* when rule 6 finds one) |
| ignored | *Ignored: time in the future* / *Ignorada: hora futura* | — |
| incomplete dose inside | *· Incomplete dose* / *· dosis incompleta* appended | as its status |

*Review* (rule 11) opens this page filtered to that read: top line *23 injections logged on 23 Oct* / *23
inyecciones registradas el 23 oct* and the pill *Undo this read* / *Deshacer esta lectura*; each row says *New* /
*Nueva* or *Replaced your 8u entry* / *Sustituyó tu entrada de 8u*; a row opens §4.3 (correct or delete one).

### 4.10 Debug (screenshots, emulator only, `DebugFakes.run`)

`DebugReceiver --es cmd penRead --es stage reading|logged|several|grew|nothing|pending|unclear|same|big|clock|failed|notusable|newpen|returning|untested`
plus `--es cmd penEntry` (opens §4.3 with a fake 4-dose entry), `penQuestion` (card + notification), `penHistory`.

## 5. Journeys (states and exits)

1. **First scan of a new pen**: unlock → tap → card *New pen added* (rapid) → optional *Log 8.5u*, optional *Set up*
   (§4.7: kind, the same offer). Nothing blocks; nothing older is logged (Doses read has it). Exits: the pen page
   changes kind / forgets.
1b. **Rapid + long after lunch**: tap the rapid pen → card *8.5u logged* → *Add treatment* → Long focused, time 13:12
   → *Save*. Or tap a second (long) pen: its own card.
2. **Everyday background scan**: in any app, tap → read sheet *8.5u logged* (merge line if any) → closes by itself.
   Exits: *Undo* (sheet), later §4.3 Delete / correct, Doses read *Not logged* → *Log this injection*.
3. **Scan with Sukrara open**: same read sheet over Sukrara (one reader, also from the sensor screen's reader mode);
   the new row animates into the list; *Open* scrolls to it.
4. **Top-up**: right after (≤ 10 min) → in the same entry (*Now 8.5u · 0.5u added* if the first part was already
   read). Later than 10 min and small → §4.4 with *Log 1u* suggested as its own entry.
5. **Unclear doses**: sheet *Priming or injected?* → *Log 1.5u* or *Check*; closed unanswered → card + notification
   until answered. Exits: any answer; change it afterwards in §4.3.
6. **Correction**: home row → §4.3 → flip a segment → *Save* → snackbar *Undo*. Exits: *Cancel*, *Undo*, flip back.
7. **Hand-typed duplicate**: typed before the read → merged on read, said on the sheet, undoable. Typed after the read
   → §4.6. Ambiguous → §4.5. Caregiver / Mac entries are candidates too (*Logged by Ana* / *on the Mac*).
8. **Failed read**: red sheet, nothing logged, tap again. NFC off / blocked / Juggluco: nothing happens on tap
   (Android gives us nothing), so the Insulin pens page and its overview summary show it with the fix.
9. **Second pen**: §4.7 again; rows and sheets name the pen when there are two; kinds per pen (rapid + long).
10. **New phone / reinstall**: entries come back through sync; first tap → returning pen (§4.7), newer doses
    imported, older than 72 h → review.
11. **Turning it off**: switch off → taps do nothing, pens and entries stay; on again → next tap imports the gap
    (review if > 72 h). Forget a pen → its entries stay; tapping it again = returning pen.
12. **Follower phone**: no Insulin pens row; a pen tap does nothing (component never enabled for followers); pen
    entries show *from the pen* and the read-only §4.3.

## 6. Untested pens (the user's addition)

Any pen the OpenNov reader identifies whose model isn't *NovoPen Echo Plus* (NovoPen 6 and other models the protocol
reports) is **supported but untested**, `PenProfile.verified = false`, like sensor families. The Echo Plus becomes
verified with the user's first real scan in this round.

- First tap: nothing is logged and nothing opens; the card says *Untested pen added* / *Pluma sin probar añadida* ·
  *NovoPen 6 · check it once before Sukrara logs it* / *NovoPen 6 · compruébala una vez para que Sukrara la registre*
  with *Set up*, and the pen row says *Check it to start* until then (the notification carries the same). *Set up*
  shows the sensor flow's red **Untested pen** confirmation (`untested_confirm_*` pattern, 10 s
  *Continue anyway (10)*, *Cancel* tonal): *Sukrara's support for NovoPen 6 is untested: doses may be read wrong.* /
  *El soporte de Sukrara para NovoPen 6 no está probado: las dosis pueden leerse mal.* Cancel = stays unchecked (nothing logged; *Set up* again any time).
- Then §4.7 adds one **check with the pen's display** before *Done*: *Remove the cap: does your pen show 0.5u and 2
  hours?* / *Quita el capuchón: ¿tu pluma muestra 0.5u y 2 horas?* (the newest dose, priming included, and the whole
  hours since: what the display shows), buttons *It doesn't* / *No coincide* and *It matches* / *Coincide*. Matches →
  the pen works like any other (entries upload normally: the user verified the amount themselves, and they know what
  they injected). Doesn't → *Sukrara doesn't log this pen* + *Send feedback*; the pen is listed *Not used · doesn't
  read right* / *Sin usar · no se lee bien* with *Check again* (the next tap asks again).
- The red *Untested* tag shows on the pen list row, the pen page header, §4.7 and §4.3's pen line. **Not** on home
  rows and no home banner (the user sees doses daily; the tag where the pen is managed is enough, and red on every row
  would read as an error).
- *Send feedback* on the pen page (GitHub issue template `pen-feedback.yml`, label `pen-feedback`: model, Sukrara,
  Android, phone; no doses, no serial).
- Not supported at all: Bluetooth pens and clips (InPen, Mallya, Tempo): out of scope (§8).

## 7. Mac

Shows (already built on `pen29-mac`, 0619cd1a; reviewed in §10): the row *8.5u rapid insulin* · *From the pen · 1.5u
primed*, a disclosure with each dose and its time (priming in secondary colour, *1u primed*), only when there is more
than one dose. No correction or delete on the Mac for phone entries (writer scope), so no editor.

Requested changes:
1. Tooltip: drop the serial (pointless detail; two pens are told apart on the phone): *Read from the pen on Ana's
   phone*.
2. VoiceOver: the row reads "8.5 units rapid insulin, from the pen, 1.5 units primed, 12:40, 47 minutes ago"; dose
   rows "12:37, 1 unit, primed".
3. Add-treatment dialog: rule 7's warning *Already logged by the pen?* · *8.5u rapid at 12:40* · *Cancel* / *Log
   Anyway* (Mac title case), so the Mac can't create the duplicate the phone prevents.
4. An entry with no injected dose never exists (priming alone makes no entry); a merged entry keeps its note in the
   row as today.

## 8. Out of scope

Priming statistics / insulin-per-cartridge, insulin on board or bolus advice from pen data, reading on the lock screen
if Android doesn't dispatch (we document it), a movable start date, bulk import of years, editing a pen dose's amount
or time (the pen is the truth; delete + type by hand if ever needed), Mac corrections, follower-phone reading,
Bluetooth pens, a second priming knob (time windows stay fixed), LibreView-only hand entries (Sukrara can't see them;
the first-scan boundary keeps them safe).

## 9. QA acceptance checklist (I review the screenshots against this)

Synthetic data only, emulator, en and es, light and dark, 360 dp and 412 dp; TalkBack pass on the starred ones.

| # | Scenario (debug stage / doses) | The user must see |
|---|---|---|
| 1 | Insulin pens off (default) | Overview *Insulin pens · Off*; page intro, switch off, no pen rows |
| 2 | On, no pen yet | *Your pens* empty line; priming row *2u* with the rule footer |
| 3 | NFC off / blocked / Juggluco installed with pen reading | the matching status row with its fix; overview summary says *Blocked in Android settings* when blocked |
| 4★ | First tap of an Echo Plus with 412 old doses, newest 8.5u 4 min ago, a hand 8u at 13:10 | Sukrara doesn't open; card *New pen added* · *NovoPen Echo Plus · rapid · 412 earlier doses left out* · *Matches your 8u entry*, *Log 8.5u* + *Set up*; nothing logged until *Log 8.5u*; *Set up* opens §4.7 with the same offer |
| 5 | Same, *Log it* | pill becomes *Logged*; home list shows one `8.5u rapid · from the pen` (not two) |
| 6★ | Background tap: `1, 0.5, 8, 0.5` | entry logged with no confirmation; card *8.5u logged* · *Rapid · 13:12 · 1.5u primed*, *Add treatment* + *Undo*, no scrim, the app behind stays usable, closes by itself; a low-importance notification with the same text and actions (no heads-up, no sound) |
| 6b★ | *Add treatment* after a rapid read (card, notification, §4.3) | Quick treatment over the current app, *When?* = *Today 13:12*, focus on Long; saving 14u long logs it at 13:12; typing 8.5u rapid there triggers §4.6 |
| 7 | `5, 0.5, 0.5, 1, 3` (split around a cartridge) | *8u logged* · *2u primed*; §4.3 shows 5 Injected, three Priming, 3 Injected |
| 8 | Merge with a hand 8u at 13:05 | sheet line *Replaced your 8u entry at 13:05*; list has one row with the hand entry's note |
| 9 | *Undo* on 8 | *Undone*; the hand 8u entry is back as it was; Doses read shows *Not logged* |
| 10 | Rescan, nothing new | *Nothing new* · *Last injection: 8.5u at 13:12*; list unchanged |
| 11 | Scan after a 0.5u top-up 3 min later | *Now 8.5u* · *0.5u added*; one row |
| 12 | Only `1, 0.5` 5 min ago | *Only priming so far*; no entry, no card yet |
| 13★ | `1, 1.5` with no injection after (30 min passed) | sheet / card / notification *Priming or injected?* · *1u and 1.5u at 13:02*; *Log 1.5u* logs 1.5u at the 1.5u's time; the card and notification disappear together |
| 14 | 13 answered *All priming* | no entry; Doses read *Only priming · 2.5u* |
| 15 | Small 1u 45 min after an 8u injection | asks (not added to the 8u entry); suggestion *Log 1u* |
| 16 | Priming 15 min before injecting (two sessions) | one entry, *1.5u primed*, time = the injected dose |
| 17 | Two injections 40 min apart | two rows, each with its own priming |
| 18★ | §4.3 correction: flip the 0.5u top-up to Priming | header updates live to *8u* · *2u primed*; *Cancel* / *Save*; after Save snackbar *Entry changed* · *Undo*; graph mark shows 8u |
| 19 | §4.3: all doses Priming | header *Nothing injected*; button *Remove from log*; snackbar with *Undo*; Doses read *Only priming* |
| 20 | Corrected entry, later scan adds a dose | it asks instead of changing the corrected entry |
| 21 | Two hand candidates (5u 13:00, 6u 13:20) for 8.5u 13:12 | *Same injection?* with two radio rows + *None of these*; nothing logged until answered |
| 22 | Hand 3u at 13:05 vs pen 8.5u | *Same injection?* (amount outside tolerance) with *Different injections* / *Same injection* |
| 23 | Caregiver's hand entry is the candidate | *Logged by Ana · 8u at 13:05* |
| 24★ | Type 8u rapid 10 min after a pen entry | *Already logged by your pen?* · *8.5u rapid at 13:12*; *Cancel* returns with the values; carbs in the same save are saved |
| 24b★ | Add treatment open, tap the pen after `1, 0.5, 8, 0.5` | before: the small contactless-pen icon in the title row (TalkBack label); after: Rapid = 8.5 with *From the pen · 1.5u primed*, *When?* = *Today 13:12*, nothing logged yet; *Save* logs one pen entry (merge snackbar when a hand entry matched); *Cancel* logs nothing and a later tap logs it |
| 24c | Same with a long pen; with `1, 1.5`; typing over the filled value; nothing new; failure | Long filled; suggestion with *1u primed · tap to change*; mark gone; *Nothing new* / red *Couldn't read the pen* for 3 s, fields untouched |
| 25 | Failed / partial read | red sheet *Couldn't read the pen*, REJECT haptic, nothing in the list |
| 26 | Pen not scanned for 5 days | logged at once; card *23 injections logged* · *Since 2 Oct · 18 replaced your entries*, *Review* + *Undo*; Review = Doses read filtered to that read |
| 27 | Pen clock reset | *Pen clock changed*; no new entries; Doses read shows the doses |
| 28 | Future-dated dose | not logged; Doses read *Ignored: time in the future* |
| 29 | Incomplete dose flag | counted; *Incomplete* tag in §4.3 and Doses read; no question |
| 30 | Doses read with every status | each status of §4.9 with its summary; *412 earlier doses* pill folds / unfolds; *Log this injection* on an old one shows the merge line |
| 31 | Second pen (long) | §4.7 again with Long chosen; rows say *From <name>* in §4.3; pen list shows both |
| 32 | Two pens of the same model | default names differ by `·9KE` |
| 33 | Returning pen (new phone) | §4.7 *Already in your log up to yesterday 21:40…*, kind preset |
| 34 | Forget pen | dialog; entries stay; next tap = returning pen |
| 35 | Turn reading off | taps do nothing; pens and entries stay; summary *Off* |
| 36★ | Untested NovoPen 6 first tap | red *Untested pen* dialog with the 10 s countdown; then §4.7 with *Untested* tag and the display check |
| 37 | Untested, *It doesn't* | *Sukrara doesn't log this pen*, *Send feedback*; list *Not used · doesn't read right*, *Check again*; later taps show the not-usable sheet |
| 38 | Untested, *It matches* | normal reads; *Untested* tag on pen row / page / §4.3, not on home rows |
| 39 | Follower phone | no Insulin pens row; pen entries read-only in §4.3; tapping a pen does nothing |
| 40 | Mac | row + disclosure + tooltip without serial (§7); VoiceOver labels; add dialog warning |
| 41 | es everywhere | every string of §4 in Spanish, *pluma*, *purgado*, no English leftovers; amounts `8.5u` |
| 42 | TalkBack | read sheet title announced; auto-close waits for focus; dose rows' *Mark as injected / priming* actions; no "u" read as a letter |
| 43 | Outputs | LibreView, Nightscout, Tidepool, graph, insulin mute: the injected amount only; priming nowhere |

## 10. Review of the Mac snapshots (pen29-mac, 0619cd1a)

Row and detail (light, dark): match §7 except the tooltip serial (#1) and need the VoiceOver labels (#2) and the add
dialog warning (#3). Priming doses in secondary text, injected in primary, total time = first injected dose (12:40),
disclosure only for multi-dose entries: all correct. "From the pen" matches the phone's *from the pen*; keep it.
