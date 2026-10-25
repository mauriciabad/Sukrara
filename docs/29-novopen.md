# 29 — NovoPen Echo Plus: tap the pen, the insulin is logged

*2026-10-23 · investigation report · standalone PoC `spikes/novopen-android` (Kotlin JVM: the OpenNov reader ported
from xDrip+, the pen dose engine, 50+ JSON scenarios) · contract `spikes/novopen-android/DESIGN.md`, product spec
`PRODUCT.md` · source read from the local checkouts in `third_party/`: xDrip+ (`insulin/opennov`, `PrimeDetection`),
Juggluco (`NovoPen/`, `javasettings.cpp`, `novopens.hpp`), nov-open-reader (lcacheux, cloned to scratch) · no real
pen read yet: every number below comes from xDrip's recorded transcripts and an emulated pen*

## Goal

The user has a **NovoPen Echo Plus** (NFC, 0.5u steps, ~800 doses in memory, its own clock) and has typed every dose
by hand for a long time. They want to hold the pen to the phone and have Sukrara log the insulin, from any app,
with priming separated from what was injected, and **never a duplicate, never a lost dose, never the old history in
the log**.

## Short answer

- **The protocol is solved upstream.** NovoPen 6 and Echo Plus speak IEEE 11073-20601 (PHD) over an NFC Forum Type 4
  tag ("OpenNov"). xDrip+'s implementation, with Juggluco's fixes, is now plain Kotlin with a `Transceiver` interface
  (`spikes/novopen-android/src/main/kotlin/app/mauri/novopen/opennov`), and all of xDrip's tests and transcripts pass
  on it (37 JVM tests, plus an emulated pen that replays the recordings end to end).
- **The hard part is the rules, not the radio.** A pen records every press: priming ("air shots", 0.5–2u, often
  several), the injection, a top-up, a split dose across a cartridge change. A pure engine (`PenEngine`) turns a read
  plus a ledger plus the treatment log into entries and the rare question; every rule is a scenario in
  `scenarios.json` (53), run by the spike **and** by Sukrara's unit tests.
- **Duplicates are handled by keys, not by time.** A dose's key is the pen's raw relative time + units: identical in
  every read, so a rescan changes nothing. Hand-typed entries are matched (±45 min, amount within max(1u, 25 %)) and
  upgraded instead of doubled; ambiguous cases ask.
- **Old history stays out.** The first scan of a serial sets the start; the 400+ older doses in the pen are listed in
  *Doses read* and never logged unless the user logs one, with one exception offered by a tap (*Log 8.5u*: the newest
  injection, if under 24 h old).
- **Android only reads tags unlocked.** A tap on a locked phone does nothing: the copy says "Unlock your phone … until
  it vibrates. No vibration? Nothing is lost: the next tap catches up."

## 1. The pen over NFC

**Techs** (confirmed on the user's Pixel with the real NovoPen Echo Plus, 0.11.45, 2026-10-23): the pen is a Type 4 tag
(NDEF application `D2760000850101`, files E103 / E104, the PHD messages inside an NDEF record of type "PHD"), but
**Android's own NDEF check times out on it**. The phone's log of the tap:

```
NfcService: Tag detected            T4T ISO_DEP, tech A
nativeNfcTag_doCheckNdef ... NFA_NDEF_DETECT_EVT status = 0xB2   → NDEF detection timed out
findAndReadNdef: Check NDEF Failed
NfcDispatcher: dispatchTag: TAG: Tech [android.nfc.tech.IsoDep, android.nfc.tech.NfcA] message: null
activities.size() = 0 → no match
```

So the tag is dispatched as **IsoDep + NfcA without Ndef**, and 0.11.45's filter (which also required `Ndef`) never
matched: a background tap did nothing. xDrip reads with `FLAG_READER_NFC_A | FLAG_READER_SKIP_NDEF_CHECK`
(`NFControl.java:31`), which never hits the check; Juggluco treats any IsoDep tag as a pen (`MainActivity.java:1069`).
Since 0.11.46 Sukrara's filter (`res/xml/nfc_tech_pen.xml`) is:

```xml
<tech-list>
    <tech>android.nfc.tech.IsoDep</tech>
    <tech>android.nfc.tech.NfcA</tech>
</tech-list>
```

It also matches EMV bank and transit cards and passports (IsoDep + NfcA); the Libre sensor (NfcV) never matches. So
`PenReadActivity` shows nothing until the tag answers the pen's application / PHD file selection: a tag that doesn't
(`NotAPenException`, `PenTag.outcome` = `NOT_PEN`, `PenTagTest`) is finished silently: no card, no vibration, no
notification, nothing logged. Only a tag that answered the selection and then broke off gets the failure card.
Sukrara's own reader modes (treatment dialog, sensor screen) use `FLAG_READER_NFC_A | FLAG_READER_SKIP_NDEF_CHECK`
and never wait on the NDEF check either.

**Android 17 protects NFC dispatch** (0.11.46 on the user's Pixel, 2026-10-23): with the filter matching, the tap still
went nowhere: `W/NfcDispatcher: Activity app.mauri.sukrara.pen.PenReadActivity does not have DISPATCH_NFC_MESSAGE
permission` → `no match`. From Android 17 an app targeting SDK > BAKLAVA only gets NFC intents in activities protected by
`android:permission="android.permission.DISPATCH_NFC_MESSAGE"`, so only the NFC service can start them
([NFC basics](https://developer.android.com/develop/connectivity/nfc/nfc)). The Libre tap (an activity alias of
`MainActivity`) had the same bug. Since 0.11.47 both taps land on tiny protected no-UI trampolines (`pen/PenTapActivity`
→ `PenReadActivity`, `ui/SensorTapActivity` → `MainActivity`), because Sukrara can't hold that permission and so can't
start a protected activity itself (its reader modes and debug reads start the unprotected `PenReadActivity`).
`NfcManifestTest` fails if any activity or alias with an NFC intent filter lacks the permission.

**Juggluco catches it too.** Its manifest lists `NfcV`, `Ndef` and `NdefFormatable` as separate tech lists
(`Common/src/main/res/xml/nfc_tech_filter.xml`), so a pen tap with both apps installed shows Android's app chooser.
Settings → Insulin pens shows *Juggluco also reads pens* with a link to its App info; Android 16's per-app *NFC tag
intents* setting turns Juggluco's tag launch off without touching its foreground reader. Sukrara's own reader mode
(treatment dialog, sensor screen) takes the tap first and doesn't play the system sound
(`FLAG_READER_NO_PLATFORM_SOUNDS`).

**Locked / screen off.** AOSP's NFC service polls for tags only with the screen on and unlocked; the newer mainline
module's only locked polling is the limited mode for system lock-screen features. *Require device unlock for NFC*
(Secure NFC) only governs card emulation, not reading ([AOSP Secure NFC](https://source.android.com/docs/core/connect/secure-nfc),
[GrapheneOS #3438](https://github.com/GrapheneOS/os-issue-tracker/issues/3438) on a Pixel: tags read only unlocked).
Conclusion for a Pixel on Android 16/17: **no dispatch when locked or screen off**; to be confirmed on the user's
phone. A lock-screen read was never built.

**Through a case or the cap.** NFC at 13.56 MHz works through a few millimetres of plastic or leather; the pen's
antenna is in the dose-button end. A thick wallet case with cards inside blocks it (a card's own antenna detunes the
field), which is why the second failure's tip says *take cards out of the case*. Not tested on a real pen: the user
confirms on the Pixel (cap on, the case they use).

**Clock, times, flags.** The pen has no wall clock: each dose carries a relative time in seconds and every data
chunk carries the pen's current relative time, so a dose's absolute time = the phone's time at the chunk − (pen now −
dose time), as xDrip and Juggluco compute it. It shifts by a second or two between reads, hence the key on the raw
relative time. Each record is 12 bytes (relative time, units as an 11073 FLOAT with exponent −1, i.e. 0.1u
resolution, a 32-bit flag word). Every normal dose in the recordings reads `0x08000000`; xDrip keeps only that,
Juggluco any low byte. Sukrara follows Juggluco (a dose with a status bit isn't lost) and marks a non-zero low byte
*Incomplete* (counted with the units the pen reports): **the meaning of those bits is undocumented**, to be checked on
real reads. Records that aren't doses (relative time 0, NaN units, `0x04000008`) are dropped. A dose time after the
scan + 2 min is *Ignored: time in the future*. No source describes a pen clock reset; the engine treats a pen clock
that went backwards as one and logs nothing new from that read (it waits in *Doses read*).

**Read speed** (counted on the emulated pen): a session with 3 records is 39 APDUs; each further chunk of 18 doses
5 more. A full 800-dose memory ≈ 45 chunks ≈ 260 APDUs, about **3–8 s** at 10–30 ms per APDU, hence *The first read
takes a few seconds.* Later reads stop early once a chunk reaches doses already known (Juggluco's trick:
`stopBefore`, minus 2 h for sessions still open): about 40 APDUs, under a second.

**Partial reads.** xDrip reports any session end as success. The port throws when the pen isn't selected, the
session breaks off or the transfer is unfinished, so **nothing is processed from an incomplete read** (*Couldn't read
the pen · Your doses are safe in the pen. Tap again and hold it still.*).

**Model.** Both models send the same model string ("Novo Nordisk A/S NovoPen"); only the part number differs and
we have no table yet. `PenProfiles.identify`: a known part number, else a half-unit dose (only the Echo Plus dials
0.5u) → *NovoPen Echo Plus* (verified for this round, the user's pen), else *NovoPen 6* (untested: nothing is logged
before the user compares one read with the pen's display). **Record the part number of the user's first real read
here** and add it to `PenProfiles.PARTS`.

## 2. What the others do about duplicates and history

| | Duplicates | Old history | Priming |
|---|---|---|---|
| xDrip+ (`SaveCompleted.java`) | uuid `serial:Open<relTime>:<units>` | imports doses under a week old; older than 12 months or in the future invalid | opt-in: ≤ N u followed by a dose within M min is zeroed (`PrimeDetection`); orphans ignored |
| Juggluco (`javasettings.cpp:1589` `savedoses`, `Scan.java`) | per-pen `lasttime` watermark: doses at or before it stop the import | first scan asks "Only add doses from after:" (a date picker) and the insulin type; skips > 100 days, future, > 60u | a dose ≤ 2.0u less than 60 s before the next is dropped |
| nov-open-reader | none | everything | groups doses ≤ 60 s apart, leaves out leading ≤ 2u doses |
| LibreLink (forums) | — | — | every press imported; users fix airshots by hand |

Juggluco's watermark loses a top-up read before its injection; xDrip's one-week window imports a week of doses the
user already typed. Sukrara keys every dose (xDrip's idea) and keeps a ledger, so a rescan is a no-op and nothing
older than the first scan is ever logged on its own.

## 3. The dose rules (as built)

All in `PenEngine` / `PenRules` (every window in one place), all scenario-tested:

1. **Session** = doses ≤ 10 min apart.
2. In a session with a dose of at least the priming size (`penPrimeBelow`, default **2u**), small doses **before the
   last large dose are priming**, small doses after it are injected (a top-up): `1, 0.5, 8, 0.5` → 8.5u injected,
   1.5u primed; the split dose around a cartridge change `5, 0.5, 0.5, 1, 3` → 8u / 2u. Entry time = first injected
   dose.
3. A small-only session ending ≤ 30 min before an injection is that injection's priming.
4. Every other small-only session is **asked** (*Priming or injected?*) with the suggestion "the last dose injected,
   the ones before it priming"; while it is the newest and under 30 min old it waits (*Only priming so far*), then a
   timer (WorkManager, `PenEngine.nextWake`) turns it into the question.
5. **Hand-typed entries** of the same kind within 45 min: one within max(1u, 25 %) → replaced; several or a mismatch →
   *Same injection?*. The wearer's own entry (`o` phone / mac) is upgraded in place (id, note kept); a **writer's**
   (a caregiver's, `o` follower) is tombstoned and a new pen entry of the wearer's takes its note (protocol review: a
   writer may edit its own entry and could drop the pen keys).
6. Typing rapid / long within 45 min of a pen entry of that kind → *Already logged by your pen?*.
7. **User edits win**: an entry whose `u` isn't the engine's last write, or whose `pd` roles differ from the ledger's
   (a correction on the other device), is never rewritten; a dose that would join it is asked.
8. Every dose of a pen entry can be set *Priming* / *Injected* (the entry's sheet); all priming = *Remove from log*.
9. First scan: start = now; the earlier doses stay out (*Doses read*, *Log this injection* one at a time); the newest
   injection under 24 h is offered (*Log 8.5u*). A lost ledger (new phone, reinstall, a forgotten pen) is rebuilt
   from the pen entries already in the synced log: the start becomes the newest known pen dose, so nothing is
   re-imported or dropped.
10. A read that logs an injection older than 72 h logs it anyway and is flagged (*23 injections logged · Review*).
11. A `pd` holds at most 20 doses; a longer session is split into entries of ≤ 20.

## 4. The PoC

`spikes/novopen-android`: `./gradlew test` (JDK 17) runs the OpenNov port's 37 tests (xDrip's MessageTest, the link
layer tests, the emulated pen) and `PenScenarioTest` over `scenarios.json` (53 scenarios: the user's examples,
cartridge split, Novo's 2u flow checks with the size at 2.5, xDrip #4770's run of primes, top-up, priming then
distraction, two injections 40 min apart, all-small, a growing session, pending → question, folding a timed-out
question, first scan with 400 old doses and the 24 h offer, rescan idempotence, duplicates in the pen memory, an
incomplete dose, delete then rescan, delete then grow, merge in place, a writer's entry replaced, tolerance, two
candidates, amount mismatch, a growing session with an open question, edit then grow, a hand `a` edit, phone and
other-device corrections, remove + undo, clock reset, a big read, future doses, long pen, two pens, the reverse
warning, lost ledger, returning after 5 days, undo of a merge and of a creation, logging from the history, before
the start, forget, an oversized session).

## In Sukrara

Android (Kotlin, `app/src/main/kotlin/app/mauri/sukrara/pen/`, `ui/Pen*.kt`); the Mac shows and keeps the pen keys
(Mac agent, `pen29-mac`).

| Added | Where |
|---|---|
| The OpenNov reader and the dose engine, copies of the spike's (package renamed; `PenCopyTest` fails when they drift); the spike's scenarios run in the app's unit tests | `pen/opennov/`, `pen/PenEngine.kt`, test `pen/PenScenarioTest`, `PenCopyTest` |
| The ledger `pens.json` (phone only, never synced), applying outcomes (ledger first, then the entries), the read pipeline, untested gating, the treatment-dialog read | `pen/Pens.kt`, `pen/PenProfiles.kt`, `pen/PenTag.kt` |
| Background read from any app: `TECH_DISCOVERED` filter (IsoDep + NfcA; 0.11.45 also required Ndef, which the pen never shows: Android's NDEF check times out) with a silent finish for tags that aren't a pen on a translucent no-history activity, enabled only while reading isn't *Off*; a card at the bottom (no scrim, the app behind stays usable) that closes by itself after the accessibility timeout | `pen/PenReadActivity.kt`, `ui/PenUi.kt` `PenReadCard`, manifest, `res/xml/nfc_tech_pen.xml` |
| Treatment keys `pr`, `pen`, `pd` through store, merge, BLE/Nostr publish, writer merges; malformed ones reject the entry (negative `pr`, `pen` > 32 bytes, `pd` > 20 items / bad items / out of order) | `treatments/Treatments.kt`, `wearer/WearerExtras.kt`, `TreatmentPenKeysTest` |
| LibreView, Nightscout, Tidepool, CSV, graph and the insulin mute use `a` only; the cloud uploads of a pen entry wait until its session closes (10 min after its last dose), so a top-up isn't a retraction + re-upload | `Treatment.heldUntil`, `LibreViewUploader`, `NightscoutQueue`, `TidepoolQueue`, `pen/PenWork.kt` |
| Questions: home card above the glucose card, silent notification with *Log 1.5u* / *All priming*, sheets *Priming or injected?* / *Same injection?* | `ui/PenUi.kt`, `pen/PenNotifier.kt` |
| The pen entry's sheet: doses with *Priming* / *Injected* (TalkBack custom actions), *Save* / *Remove from log* with an undo snackbar, *Delete*, *Add treatment*; read-only on followers | `ui/PenUi.kt` `PenEntrySheet`, `DoseRow` |
| *Already logged by your pen?* on Save; the pen read into the treatment dialog (reader mode, no system sound) | `ui/PenDialog.kt`, `ui/TreatmentDialog.kt` |
| Settings → Insulin pens (Ask / On / Off, NFC off / blocked / Juggluco rows, pens, priming size), pen page, *Doses read* (30-day totals, statuses, earlier doses folded, *Log this injection*, *Review* of a big read with *Undo this read*), setup with the untested confirmation and the display check | `ui/PenSettings.kt`, `spikes/settings/settings.json` (`pens.*`, `penPrimeBelow`) |
| *Add treatment* from a read: the quick dialog at the pen's time, the other insulin focused | `QuickTreatmentActivity` extras, `PenWords.addTreatment` |
| Passive hints: *Injected? Hold your pen to the phone.*, *Next time, just tap your pen* (≤ 1/day, 3 ever), *Pen read 2d ago* | `ui/WearerScreens.kt`, `PenSnack.typedRapid` |
| Feel: one haptics helper (composition primitives, fallbacks), the card's spring-up, the failure shake, the count-up in 0.5u steps, *Remove animations* respected | `pen/PenHaptics.kt`, `PenReadCard` |
| Debug-only fake pen (`penRead`, `penReset`, `penOpen`) | `features/DebugFakes.kt` `FakePen` |
| Strings en + es (pluma, purgado) | `values*/strings.xml` |
| 0.11.51: which pens work, in the footer of Settings → Insulin pens (always shown): *Sukrara reads NovoPen Echo Plus and NovoPen 6 (untested). Other NFC pens, and Bluetooth pens or caps like Tempo, InPen or Mallya, aren't supported.*, built from `PenProfiles.ALL` (ICU list join, untested in words); the models are search keywords of the *Insulin pens* row (AOSP `settings:keywords`); README, site and FEATURES list the same | `ui/PenSettings.kt` `pensFooter` / `pensKeywords`, `ui/SettingsUi.kt` `keywords`, test `PensPageTest` |
| 0.11.50: *Automatic detection* page: the priming size plus the session window, priming ahead of time, the wait before asking, the hand-typed match window and amount (units and share) and the new-pen offer age as schema settings, *Reset to defaults*; 5 new scenarios with changed rules | `ui/PenSettings.kt` `PenDetectionSettings`, `Pens.rules`, `spikes/settings/settings.json`, `scenarios.json` |
| 0.11.52: *Notification after each read* (Settings → Insulin pens, `penNotify`, off by default): only the per-read *Pen reads* notification; *Pen doses to check* posts either way; off cancels the shown one; on but blocked → *Blocked in Android settings*, the row opens the channel page. The channel is now `pen_read_results`, default importance with no sound / vibration (no heads-up), and the read posts in its own group with a summary (the old low-importance `pen_reads` is deleted) | `pen/PenNotifier.kt`, `ui/PenSettings.kt`, test `pen/PenNotifyTest` |
| 0.11.52: the treatment dialog's pen icon is gone (title row back to *Add treatment* + *When?*); the dialog still reads a held pen, and *Nothing new* / red *Couldn't read the pen* show under the fields for 3 s (polite live region) | `ui/PenDialog.kt` `PenReadWord`, `ui/TreatmentDialog.kt`; deleted `ic_dia_pen_nfc.xml`, `pen_dialog_read_a11y` |
| 0.11.50: *Replace your entry* / *Replace with 8.5u* instead of *Log it* / *Log 8.5u* / *Log this injection* when the dose matches a hand-typed entry (it replaces it, never doubles) | `ui/PenSettings.kt`, `ui/PenUi.kt` |

| Discarded or changed | Why |
|---|---|
| DESIGN's "every dose after the first large one is injected" | a split dose around a cartridge change (`5, 0.5, 0.5, 1, 3`) logged 10u; priming now = small doses before the **last** large one (PRODUCT §3.2) |
| DESIGN's automatic top-up 10–60 min later | a 1u correction 45 min after a meal bolus is a separate injection; summing moves insulin 45 min earlier in LibreView and the graph. Asked instead, suggestion *Log 1u* |
| Movable start date | replaced by *Doses read* with *Log this injection* per session: one place, no date picker, no bulk import of years |
| Holding a big read (> 72 h) for review | the user: a pen read is never confirmed and nothing blocks; it logs and is flagged with *Review* + *Undo* |
| Merging into a writer's entry in place | a writer may later edit its own entry, and a third-party writer drops unknown keys: tombstone + a new wearer entry |
| Feel (§4.11) as specified | built: the reader reports progress (records transferred / records held), so the wavy ring fills per segment with a tick each; check pop, count-up, primed chips peeling, failure shake, correction ghost with counting totals, dialog count-up + icon pulse; *Remove animations* turns motion off. Simplified: the setup reuses the sensor scan's antenna drawing (a sensor on the spot) |
| Lock-screen read | Android doesn't dispatch tags locked (above) |
| The fixed guards as settings (future doses 2 min, clock-reset matching, 20 doses per entry, the 72 h big read) | safety limits of the engine, not user preferences; tuning them could double or drop doses |
| The success notification heads-up | default importance without sound or vibration never peeks; replaced per read, 2 h timeout, private on the lock screen (*Pen read*) |
| The read notification on by default, on a low-importance channel (until 0.11.51) | the user saw it "collapse" the glucose card: Android 16+ autogroups an app's ungrouped notifications once 2 share a section (`GroupHelper`), and both were in the silent section. Off by default now (the card and the haptic already say it) |
| Default importance alone (the user: the high glucose alert doesn't do the weird grouping) | measured with `dumpsys notification` on API 36 / 37: it left the card's section but was then bundled with a high glucose alert in `g:Aggregate_AlertingSection` (an alert inside a pen bundle is dangerous). Its own group with a summary keeps it out of both bundles, chip on and off; no sparse/singleton re-merge seen. The same autogrouping still bundles the alerts with *Sensor ready* / the sensor countdown (pre-existing, not pen related; noted in FEATURES "Live notification") |
| The small contactless-pen icon in the treatment dialog's title row | the user: "simply remove it"; the passive hints stay the discovery path |
| Bluetooth pens and caps (InPen, Tempo, Mallya) | a different protocol each; out of scope |
| 0.11.50's *Supported pens* group (one non-tappable row per model, pen icon, red *Untested* tag), deleted in 0.11.51 | it broke the AOSP Settings pattern: rows are things you act on, styled exactly like the tappable *Your pens* rows, so it read as a second list of your own pens; red is for errors; a whole group of static product info on the page. AOSP puts "which devices work" in the page's footer (Hearing devices: `accessibility_hearing_device_footer_summary`, footers `searchable=false`) and makes a page findable by words it doesn't show (`settings:keywords`): that is what replaced it |

**Main thread (ANR A/B, 2026-10-23).** The emulator showed "Sukrara isn't responding" (*executing service
OwnerService*, *No response to onStartJob*) while the host was loaded (load ~12, several Gradle builds). A/B on the same
emulator at load 2–7, a cold start and 3 minutes of use each: `main` 00a5fb64 and this branch, no ANR either. A debug
watchdog (`core/MainWatchdog`: StrictMode + the main thread's stack when it stalls > 1 s) found one 1 s stall in
Compose layout and, from StrictMode, main-thread disk I/O in pen code (`Pens.save` from UI clicks): every pen change
now runs on one worker thread. The other StrictMode hits are older code on the main thread (`OwnerEngine` init
`SecureStore.read`, `sourceSummary`, `LocalServers`), present on `main` too.

**Open risks for the first real scan**: the tech list (confirmed: no Ndef, fixed in 0.11.46); Juggluco's chooser; the flag bits; the model's
part number; the read time of a full memory; whether a cap / case blocks the read.

## Run it

```
cd spikes/novopen-android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
./gradlew test
```
