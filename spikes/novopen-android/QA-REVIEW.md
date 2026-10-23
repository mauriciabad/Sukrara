# QA review: pen29-android against PRODUCT.md §9 (round 1)

Branch `pen29-android` at 29bbe766. Emulator screenshots, Spanish, synthetic (`pen29-shots/`). English copy checked
in `values/strings.xml`; code read in `ui/PenUi.kt`, `ui/PenSettings.kt`, `ui/PenDialog.kt`, `pen/*`.
Verdicts: **PASS**, **FAIL** (what + fix), **NOT SHOWN** (no usable screenshot; retake).

## Must-fix before release

1. **ANR** on home with the merge card (08), undone (09) and the entry sheet (18, 18b). Whatever blocks the main
   thread there (a store / ledger read in composition or in the card's click) must move off it; 29bbe766 may cover
   it: retake 08, 09, 18, 18b and run once with StrictMode on. A health app that freezes after a dose is a release
   blocker.
2. **Card announcement (TalkBack, QA 6/42/58)**: `PenUi.kt:169` puts the live region on the title only, with
   `contentDescription = card.title`, so TalkBack says "8.5u registradas" and never the line ("Rápida · 2:55 · 1.5u
   purgadas", the merge line, the failure reassurance), and reads `u` as a letter. Fix: the live region and a merged
   `contentDescription` on the card's Column, built from the title + lines with `pen_units_spoken` ("8,5 unidades
   registradas, rápida, 2:55, 1,5 unidades purgadas").
3. **Same-model pen names (QA 32)**: 02 lists three identical *NovoPen Echo Plus* rows. `Pens.kt:217` adds `·<last 3>`
   only to pens added after a same-model one, so the first pen stays bare and the list is still ambiguous, and
   `pen_entry_from_named` in the entry sheet (18: *De NovoPen Echo Plus*) can't tell them apart. Fix: compute the
   display name at render time: model alone when it's the only pen of that model, `model ·9KE` (last 3–4) for every
   pen of a shared model, the user's name when renamed. If the three pens in 02 are debug-fake leftovers, clear them
   and retake with two real-looking serials.
4. **Untested display check (QA 36c)**: *Listo* is a filled button below *No coincide* / *Coincide* and is enabled
   before the check is answered, so two primary buttons compete and the user can leave thinking the pen is set up
   (it then logs nothing: *Check it to start*). Fix: while the check is unanswered show no *Listo* (✕ stays); *Coincide*
   finishes the setup (closes with the success haptic), *No coincide* shows the not-used state with *Enviar
   comentarios*.
5. **Blocked tag intents row** (`PenSettings.kt:131`) reuses the sensor's title *Open Sukrara when you scan a sensor*
   on the pens page. Fix: its own title *Pen taps are blocked* / *Los toques de la pluma están bloqueados* with the
   existing summary *Blocked · tap to allow*.

## Should-fix (this round if cheap, else next)

6. **Emphasis on the read card (06, 07, 11)**: *Deshacer* is the filled pill and *Añadir tratamiento* tonal, so the eye
   (and a hurried thumb) goes to Undo. Fix: *Añadir tratamiento* filled tonal on the left, *Deshacer* as a text/tonal
   pill on the right (both pills same weight is fine; Undo never filled). Same on the new-pen card (04): *Registrar
   8.5u* filled (it's the action that matters), *Configurar* tonal.
7. **es wording, untested**: dialog title *Pluma no probada* vs the tag *Sin probar* and *Pluma sin probar añadida*.
   Fix: `pen_untested_title` es → *Pluma sin probar*.
8. **es tense in the entry sheet (18)**: *Sustituye tu entrada de 8u de las 3:14* is right on the read card (it just
   happened) but in the entry sheet it's history: use the Doses read string *Sustituyó tu entrada de 8u* there (en
   *Replaced* works for both).
9. **Home question card pill alignment (02b, 12, 13, 21, 25)**: the single pill is centred while the text is
   start-aligned; every other card ends its pills at the end edge (read card, share card is full width). Fix:
   `Arrangement.End`. For one item the card must also show the suggestion pill (*Registrar 1.5u* + *Revisar*, §4.4):
   retake 13b with a single unclear item.
10. **Same-injection card line with several candidates (21, 25)**: *Pluma 8u a las 2:48 · tuya 8u a las 2:48* names one
    of three candidates. Fix: with more than one, the line ends *· 3 entradas tuyas cerca* / *· 3 of your entries
    nearby*.
11. **Doses read rows (30)** sit on the page background; settings groups are rounded cards (AOSP). Fix: one group card
    per day under its *Hoy* title.
12. **Pen page (31)**: the model name is both the large title and the header card's first line. Fix: large title
    *NovoPen Echo Plus* (or the user's name), header card without the repeated name (serial, since, last read).
13. **Priming row icon (02)**: the *Purgado: menos de* row uses the pen glyph like the pen rows. Fix: a distinct icon
    (Material Symbols `water_drop` outline, the priming squirt) so the page scans by icon.
14. **Stale string**: `pen_read_big_text` (*Since %1$s. Nothing is logged until you do.*) is from the superseded
    "hold big reads" rule; delete it (no backwards compat) if nothing uses it.
15. **Setup screen (36c) is missing §1b's two sentences and the NFC-spot drawing** (QA 49): add `pens_intro`'s
    sentences under the illustration and draw the antenna spot from `NfcAntennaInfo` as the sensor scan screen does.

## Later (the follow-ups the engineer listed; §4.11 Feel)

Determinate ring + per-segment ticks from the reader, primed chips peeling, correction ghost, dialog count-up; the
reading state still uses the plain `CircularProgressIndicator` (§4.11 says the wavy one, as the sensor scan).

## Checklist rows

| # | Verdict | Notes |
|---|---|---|
| 1 | NOT SHOWN | need the *Ask* / off page |
| 2 | NOT SHOWN | 02 has pens; need the empty *Tus plumas* |
| 3 | PASS (NFC off) / FAIL (blocked) | 02 NFC row correct; blocked row title, must-fix 5; Juggluco row not shown |
| 4 | PASS | 04: no app opening, *Pluma nueva añadida · NovoPen Echo Plus · rápida · 416 dosis anteriores fuera*, *Registrar 8.5u*, *Configurar*; merge line not shown (no hand entry in the shot); emphasis should-fix 6 |
| 5 | NOT SHOWN | after *Registrar 8.5u* |
| 6 | PASS (visual) / FAIL (TalkBack) | 06 matches; announcement must-fix 2; emphasis should-fix 6; notification not shown |
| 6b | NOT SHOWN | Add treatment → Quick treatment with time + Long focus |
| 7 | PASS | 07: *8u registradas · 2u purgadas* for the split |
| 8 | NOT SHOWN | ANR (must-fix 1) |
| 9 | NOT SHOWN | ANR |
| 10 | PASS | 10 |
| 11 | PASS | 11 *Ahora 9u · 0.5u añadidas* |
| 12 | PASS | 12 |
| 13 | NOT SHOWN | 13 shows the home card only, the read card is off-screen; 13b/13c stale (13c is the same-injection sheet) |
| 14, 15, 16, 17 | NOT SHOWN | engine-tested; want one screenshot of 15 (the *Registrar 1u* suggestion) |
| 18 | NOT SHOWN | ANR; what's visible matches §4.3 (segments *Purga / Inyección*, note, merge line, *Eliminar · Añadir tratamiento · Listo*) |
| 19, 20 | NOT SHOWN | |
| 21 | PASS | 21b: radio rows + *Ninguna*, *Ahora no* / *Guardar*; card line should-fix 10 |
| 22, 23 | NOT SHOWN | |
| 24, 24b, 24c | NOT SHOWN | 24 retake pending; 24a is the untested dialog, misnamed |
| 25 | PASS | 25 red card, reassurance |
| 25b | PASS | tips line on the second failure |
| 26, 27, 28, 29 | NOT SHOWN | |
| 30 | PARTIAL | 30: 30-day line PASS; only one status shown; need every status + the *dosis anteriores* pill; group card should-fix 11 |
| 30b | PASS | *Últimos 30 días: 9u inyectadas · 1.5u de purgado* |
| 31 | FAIL | three identical names (must-fix 3); pen page 31 otherwise PASS (should-fix 12) |
| 32 | FAIL | must-fix 3 |
| 33, 34, 35 | NOT SHOWN | |
| 36 | PASS | 36 card + 36b/24a dialog with countdown (title wording should-fix 7) |
| 37 | NOT SHOWN | |
| 38 | NOT SHOWN | |
| 36c | FAIL | must-fix 4 |
| 39 | NOT SHOWN | follower |
| 40 | n/a here | Mac reviewed earlier (PRODUCT §10) |
| 41 | PASS so far | every Spanish string seen is translated; *pluma*, *purgado*; amounts `8.5u` |
| 42, 58 | FAIL | must-fix 2 (code) |
| 43 | NOT SHOWN | outputs |
| 44 | PASS | 44 *¿Registrar esta pluma en Sukrara?* · *No* / *Activar* |
| 45, 46, 47 | NOT SHOWN | strings exist (`pen_hint_carbs`, `pen_hint_snack`, `pen_hint_stale`) |
| 48 | PASS (code) | `PenNotifier`: `VISIBILITY_PRIVATE` + public version |
| 49 | FAIL | should-fix 15 |
| 50 | NOT SHOWN | predictive back |
| 51–56 | LATER | listed follow-ups; haptics in `PenHaptics` match §4.11 (CLICK / LOW_TICK / CLICK+TICK / TICK / THUD / TICK with fallbacks) |
| 57 | PASS (code) | `PenHaptics` reads `ANIMATOR_DURATION_SCALE`; verify on screen once motion lands |
| 59 | n/a | Mac |

## The engine's deviations (docs/29 "Discarded or changed")

- Priming before the **last** large dose, no cross-session top-up, Doses read instead of a start date, big reads logged
  and flagged, notification low importance, no lock-screen read, no BLE pens: all as PRODUCT.md; no harm.
- **Merging into a writer's (caregiver's) entry = tombstone + a new wearer entry**: acceptable *if* (a) the new entry
  keeps the writer's note, (b) the read card / entry sheet says *Sustituye la entrada de Ana de 8u* (not *tu
  entrada*), and (c) *Deshacer* brings the writer's entry back (un-tombstones it with a new version). Without (c) an
  undo would lose the caregiver's entry: verify with a scenario test; otherwise it's a must-fix.
- **Indeterminate ring, ticks every 5th APDU**: fine for release; the "data flowing" feel waits for the reader's
  progress callback.
