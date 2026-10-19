# 28 — Choosing an insulin pump: Tandem Mobi, t:slim X2 or MiniMed 780G

*2026-10-19 · research report, no spike · sources: pumpX2 v1.9.3 (2026-10-14), controlX2 (2026-05-04), tconnectsync
(2026-10-05), carelink-bridge (NovaLux12 fork) and carelink-cumulus-bridge, all cloned to `third_party/`; vendor
pages, press releases and Catalan procurement records linked inline. Nothing was connected to a pump.*

## Goal

The user's endocrinology team at Hospital Universitari Doctor Josep Trueta (ICS, Girona) offered a pump: **Tandem
Mobi**, **Tandem t:slim X2** or **MiniMed 780G**. The questions:

1. Which sensor comes with each, here, now?
2. How far can each be integrated with Sukrara and the open diabetes tools, and where would reverse engineering
   (REA, `third_party/rea`) have to take over?
3. Which gives the most DIY freedom, and which is the best fit day to day?

The yardstick for "acceptable DIY" is the one from [23](23-sensors.md): **no vendor algorithm, no vendor identity,
the user's own secrets are fine.** One more rule for pumps: **never modify pump firmware.** A pump doses insulin, and
an update could silently undo the change.

## Short answer

- **Only the 780G keeps the Libre 3 Plus hardware, and only in its pump-locked form.** Both Tandem pumps come with a
  **Dexcom G7**. The 780G comes with **Guardian 4, Simplera Sync or Instinct**. Instinct is Abbott's Libre 3 Plus
  sensor sold under the MiniMed brand, and **Trueta already supplies it**: another 780G patient at Trueta wears one
  (the user's report, 2026-10-19). It pairs only with the pump, not with Abbott's apps or Sukrara.
- **Most DIY freedom: Tandem Mobi, by far.** Its Bluetooth protocol is open-sourced (pumpX2, MIT licence, Java on
  Android), and the pairing key comes from the 6-digit code on the pump, so it passes the "no vendor identity" rule.
  On the Mobi that protocol includes bolus, temp basal, suspend/resume and profiles. That is everything a loop needs,
  and an AndroidAPS driver and a Trio driver are in testing.
- **t:slim X2: read everything, bolus from the phone, no looping.** Non-bolus control is missing from its firmware,
  and no reverse engineering can add it without changing the firmware.
- **MiniMed 780G: closed.** Its Bluetooth protocol has never been documented in public. Data reaches a phone only
  through MiniMed Mobile → CareLink cloud → a reverse-engineered bridge, read-only, about 5 minutes late. **MiniMed
  Mobile refuses rooted phones and phones with Developer Options on.**
- **Best fit: Tandem Mobi**, with two conditions to accept:
  - **an iPhone for the official app until Android arrives in 2027.** Outside the US the Mobi app is iPhone-only;
  - **a switch to the Dexcom G7.**

  The 780G is the right answer only if the goal is *least effort and no DIY*. Its algorithm is the most aggressive
  out of the box, but it would reduce Sukrara to a cloud follower.

## 1. What Trueta can actually give you

- **How it is paid for.** CatSalut pays hospitals a daily rate per patient. The rate for an integrated pump + CGM or
  hybrid closed loop **includes the sensor**. ICS hospitals buy through one multi-vendor framework agreement,
  [CS/CC00/1101322692/23/AM](https://cido.diba.cat/contractacio/14364594/acord-marc-del-subministrament-i-suport-a-la-gestio-integral-del-pacient-amb-diabetis-bombes-dinfusio-dinsulina-sistemes-de-mcg-tires-reactives-per-a-la-determinacio-de-glucosa-en-sang-capillar-i-tires-per-a-la-determinacio-de-cetonemia-per-als-centres-de-linstitut-i-centres-adherits-generalitat-de-catalunya-institut-catala-de-la-salut-ics).
  It was awarded in 2023, has been extended twice, and its winners include Abbott, Air Liquide (Novalab, which
  distributes Tandem) and Medtronic Ibérica. The pump-and-sensor pairings listed in it could not be read: the
  procurement portal needs JavaScript.
  [CatSalut conditions 2024](https://catsalut.gencat.cat/web/.content/minisite/catsalut/proveidors_professionals/contractacio_serveis_assistencials/registre_convenis_contractes/relacio/resolucio-condicions-generals-contractacio-2024.pdf.pdf)
- **Choosing a pump in practice means switching to that pump's sensor.** A Libre 3 Plus worn as well would most
  likely be self-paid. No rule allowing two funded CGMs was found; ask.

| | Tandem Mobi | Tandem t:slim X2 | MiniMed 780G |
|---|---|---|---|
| In Spain since | 2026-09 (Novalab launch 18/09, [Tandem 28/09](https://www.morningstar.com/news/business-wire/20260928831177/tandem-mobi-automated-insulin-delivery-system-now-available-in-twelve-international-markets)) | years ([Novalab](https://www.novalab.es/bombas-insulina/tandem-tslim-x2)) | years |
| Sensor in Spain | **Dexcom G7 only**; Abbott "expected in select countries in 2027" | **Dexcom G7** ([since 2024](https://www.anisalud.com/actualidad/notas-de-prensa-anis/10747-una-gran-alianza-dexcom-g7-se-conecta-a-tandem-t-slim-x2)); Libre 3 Plus live in UK/CH/SE/FI/IT since 2026-07, **Spain not listed** ([Medical Economics](https://www.medicaleconomics.com/view/tandem-s-t-slim-x2-insulin-pump-now-compatible-with-abbott-s-freestyle-libre-3-plus-cgm-in-five-countries)) | Guardian 4, Simplera Sync, **Instinct** (Abbott, 15 days, [EU launch 2026-06-23](https://news.minimed.com/2026-06-23-MiniMed-launches-Instinct-sensors-in-Europe-giving-people-with-diabetes-more-sensor-choice-with-the-same-proven-outcomes), Spain among the first, [Canal Diabetes](https://canaldiabetes.com/minimed-sensores-instinct-instinct-go-diabetes/)); **supplied at Trueta** (a patient there wears one) |
| Official phone app | **iPhone only** outside the US; Android "2027" | t:slim mobile app (Spanish availability not verified) | MiniMed Mobile: **no root, no Developer Options**, tested phone list ([Medtronic](https://origin.medtronicdiabetes.com/customer-support/minimed-780g-system-support/troubleshooting-minimed-mobile-app)) |

## 2. How far each one integrates

### What Sukrara would see

| | Mobi | t:slim X2 | 780G |
|---|---|---|---|
| Glucose, locally | yes: from the pump over pumpX2 (`CurrentEGVGuiDataResponse`), or from the G7 in Sukrara's observer mode ([23](23-sensors.md#what-makes-the-g7-readable-anyway)) | same | **no**: cloud only |
| IOB, boluses, basal, battery, reservoir, alarms, history | yes, locally (pumpX2) | yes, locally (pumpX2) | via CareLink: glucose, IOB, reservoir, battery, sensor state, last alarm ([carelink-bridge](https://github.com/NovaLux12/carelink-bridge)); boluses/carbs not yet wired |
| Bolus from Sukrara / watch | yes (API ≥ 2.5); the pump beeps on each one | yes (firmware ≥ 7.6) | **no**, not even officially |
| Temp basal, suspend, profiles | **yes** (`MOBI_ONLY`, API ≥ 3.5) | no: not in the firmware | no |
| DIY loop | AndroidAPS: experimental driver in [Andy Rozman's fork](https://andyrozman.github.io/AAPS_Tandem_Testing/), not released; Trio: [TandemKit closed beta](https://github.com/munchyk-loop/Trio/pull/3) (fork) | none, and none possible ([AAPS](https://androidaps.readthedocs.io/en/dev/CompatiblePumps/Future-possible-Pump-Drivers.html)) | none |
| Cloud → Nightscout | tconnectsync (Tandem Source, undocumented API) | same | carelink-bridge; the Instinct needs the newer "Cumulus" CareLink API (`third_party/carelink-cumulus-bridge` README); xDrip+'s CareLink Follower is unreliable with it |
| Rule 2 (vendor identity) | **passes**: the HMAC key is HKDF(J-PAKE secret from the pump's 6-digit code), `PumpStateSupplier.java:32-50`; no Tandem certificate or key in the code | passes, same | the cloud bridges log in as the user, which is fine; a local link would almost certainly mean impersonating MiniMed Mobile |

### The two constraints that shape the Tandem design

1. **The pump holds one phone connection.** The official app and a custom app cannot be connected at the same time.
   pumpX2's README says to log out of the official app, and AAPS PR #220 calls it "the pump's single connection
   slot". Switching between them means re-pairing, and on the Mobi that needs the Qi charger. So Sukrara either
   *is* the pump's phone, or reads the pump from the cloud.
2. **A CGM paired to the pump belongs to the pump.** A Libre 3 Plus started on a Tandem pump
   [stays bound to it for life](https://www.support.freestyle.abbott/hc/en-us/articles/40512611219729), so Sukrara's
   direct Libre reading ([02](02-direct-sensor.md)) would conflict with it. The G7 accepts more than one client, so
   with the G7 it is easier: Sukrara's observer mode works if the Dexcom G7 app runs on the same phone. Whether the
   Dexcom app tolerates root is not verified. The pump also passes the reading on, so Sukrara could take glucose from
   the pump and need no sensor link at all.

pumpX2 also has `JoinFSL3Request`: the phone does the Libre 3 NFC activation and hands the sensor's Bluetooth address
and parameters to the pump. Sukrara already does that NFC step ([21](21-new-sensor-flow.md)). If Abbott support
comes to the Mobi in 2027, Sukrara could be the app that starts the sensor.

### Where REA would go, and where it wouldn't

| Territory | Worth it? |
|---|---|
| Keep pumpX2 current as Tandem ships new firmware/API versions (decompile the Mobi app, diff message tables) | **Yes**: it's the normal pumpX2 workflow, and an upstream contribution |
| Tandem Source's cloud API (tconnectsync already maps most of it) | Low value: the local link is better |
| Adding temp basal to the t:slim X2 | **No**: the commands aren't in the firmware, so it would need a firmware change |
| MiniMed Mobile's BLE protocol, for a local 780G read | Research only: undocumented, encrypted, and likely to need the app's own identity (rule 2). And the app slot is single, as with Tandem |
| Reading the Instinct sensor directly | **No**: it is bound to the pump, and a second connection would break the pump's link (carelink-cumulus-bridge README, not tested) |
| Pump firmware of any of the three | **Never** |

## 3. Day to day

| | Mobi | t:slim X2 | 780G |
|---|---|---|---|
| Size / weight | 5.1 × 3.7 × 1.4 cm, **30 g**, can be worn on the body with a 12 cm tube | 8.0 × 5.1 × 1.5 cm, 110 g | 10.2 × 5.8 × 2.8 cm, < 117 g |
| Insulin | **200 U** | 300 U | 300 U (or 180 U) |
| Power | Qi wireless charging | rechargeable | AA battery |
| Water | IP28, 2.4 m for 2 h | IP27, 0.9 m for 30 min | 2.4 m for 30 min |
| Without a phone | button: status, snooze alerts, quick bolus in steps; everything else needs the app | full touchscreen | full screen and buttons |
| Infusion set | Tandem sets, 2–3 days | same | also the **7-day Extended** set |
| Algorithm | Control-IQ+ | Control-IQ (Control-IQ+ in the US) | SmartGuard |
| Target | fixed 112.5–160; sleep 112.5–120; exercise 140–160 | same | **100**, 110 or 120; temp target 150 |
| Auto-corrections | 60 % of the correction when glucose is predicted above 180, at most once per hour, never in sleep | same | every 5 min, detects missed meals |
| Real-world time in range | — | — | 72 % for all users, 79 % with the recommended settings (n ≈ 100 k) |
| Sensor | G7: 10 days + 12 h, ~30 min warm-up | G7 | Instinct: 15 days, ~1 h warm-up, no calibration |
| What's next | Mobi Tubeless (filed with the FDA in Q2 2026, no EU date); Android app 2027; Abbott support 2027 | Tandem's older platform; the future is the Mobi | **MiniMed Flex** (half the size, phone-controlled, still closed; CE 2026-09-02, upgrade programme in some EU countries) |

Sources: [Tandem products](https://www.tandemdiabetes.com/products), [Control-IQ FDA review](https://www.accessdata.fda.gov/cdrh_docs/reviews/DEN190034.pdf),
[Mobi quick bolus](https://www.tandemdiabetes.com/community/blog/post/quick-bolus), [780G](https://www.minimed.com/en-gb/products/minimed-780G-insulin-pump),
[780G outcomes](https://news.medtronic.com/2026-01-07-Study-showed-MiniMed-TM-780G-system-achieved-ADA-recommended-time-in-range-goals-even-on-days-users-forgot-to-bolus),
[MiniMed Flex CE](https://www.intelligenthealth.tech/2026/09/02/minimed-announces-ce-mark-for-minimed-flex/).

**How it feels day to day:**

- **780G** is the most hands-off: it targets 100 and corrects on its own. It is also the bulkiest, runs on AA
  batteries, needs a second, stock phone to show anything on a phone, and gives up the data path Sukrara was built
  around.
- **Control-IQ** aims higher and corrects less. Expect a few more manual boluses, or a looser time in tight range.
- **The Mobi** is the most wearable pump of the three (30 g, clipped to the infusion site). Two costs: 200 U means
  more frequent cartridge changes if the daily dose is high, and the pump has no screen. Without the phone you only
  have the button.

## 4. Recommendation

**Tandem Mobi with the Dexcom G7.** It is the only option where:

- the pump's protocol is open and keyed by the user's own code;
- Sukrara can be a first-class pump companion on Android, showing glucose, IOB, history and boluses, sending boluses
  from the watch or lock screen, and later running a loop;
- the vendor's own roadmap goes the user's way: Android in 2027, Abbott sensors in 2027, tubeless.

How to run it:

- **Start on the official path.** Use the official iPhone app, which the hospital trains on and supports. Sukrara
  reads the G7 in observer mode and takes pump data from Tandem Source (tconnectsync), so the single connection slot
  stays with the official app.
- **Make Sukrara the pump's phone only later, as its own spike.** That means a pumpX2-based read-only link first,
  bolus later. It means giving up the official app.
- **Treat a DIY loop as later still.** The AAPS and Trio Tandem drivers are experimental forks, not something to dose
  from yet.

Pick the 780G instead only if the priority is the strongest automatic control with the least interaction, and DIY is
off the table. Keeping the Abbott sensor (as Instinct) doesn't tip the balance. Instinct is bound to the pump, so
Sukrara loses its direct reading either way. With the G7, Sukrara can still read the sensor on the phone.

## A DIY loop on the Mobi: what it can reach

Yes, the Mobi is the one pump of the three where a full DIY closed loop is technically possible. pumpX2 has every
command a loop needs: temp basal, bolus (and so SMBs), suspend and profiles. The AndroidAPS fork already drives it
this way, with Control-IQ disabled. **100 % time in range is not reachable on any system, DIY or not:**

- **Insulin is slow.** Rapid insulin under the skin starts in about 15 minutes and peaks after about 1 hour. A loop
  can only react to a rise that has already started, and food raises glucose faster than insulin can lower it.
- **The sensor lags.** The CGM measures interstitial fluid, about 5–10 minutes behind blood. On top of that the
  reading has its own error (MARD around 8 %), which is enough to put readings near the edges of the range on the
  wrong side.
- **Life has inputs the loop can't see:** an unannounced meal, exercise, stress or illness hormones, a kinked
  cannula, a site in its third day.
- **Pushing harder causes lows.** A loop tuned to never pass 180 would overshoot into hypoglycaemia. Every algorithm
  trades highs against lows.

Realistic targets:

- **Consensus goal:** above 70 % in range, below 4 % under 70.
- **Commercial loops** (780G, Control-IQ) average about 70–80 %.
- **Well-tuned open-source loops** (AndroidAPS, Trio, with SMBs and announced meals) typically reach 80–90 %. Some
  users on low-carb diets go above 95 %.
- What a DIY loop really buys over Control-IQ is your own targets (for example 100 instead of a fixed 112.5) and
  more frequent, tunable corrections. That shows most in time in tight range (70–140).

One Mobi-specific catch for an SMB loop: **the pump beeps or vibrates on every bolus sent from a phone.** With SMBs
every 5 minutes, that is the main barrier the pumpX2 author names. Temp-basal-only looping avoids it.

## 5. What Sukrara would need to support a pump

*Design sketch from a read of the app (`A` = `spikes/showcase-android/app/src/main/kotlin/app/mauri/sukrara`,
`M` = `spikes/menubar/Sources/GlucoseBar`) and of how AndroidAPS, Trio and controlX2 do it. Nothing was built.*

**Today Sukrara knows nothing about pumps.** It has one glucose source at a time (`A/owner/OwnerEngine.kt:273`) and
injection-only treatments: `k` = rapid | long | carbs | note (`A/treatments/Treatments.kt:17`). It calculates no
IOB; the local xDrip+ server even hardcodes `"iob":"0.00"` (`A/bridge/XdripServer.kt:264`). Tidepool upload
deliberately avoids `bolus`/`basal` (`A/tidepool/TidepoolData.kt`), and OGS draft 3 lists `basal`/`loop` only as
planned record kinds (`spec/draft3/ogs3-data.md:359`).

### Three steps, each useful on its own

| Step | Who holds the pump's one phone connection | Sukrara gets | Works for |
|---|---|---|---|
| **A · Read from the cloud** | the official app | pump data from Tandem Source (as tconnectsync does) or CareLink (as carelink-bridge does): read-only, minutes late | Mobi, X2, **and 780G** |
| **B · Read locally** | **Sukrara** (pumpX2) | everything, live, offline: glucose from the pump, IOB, history, reservoir, battery, alarms, Control-IQ state | Mobi, X2 |
| **C · Command** | Sukrara | bolus; on the Mobi also temp basal, suspend/resume, sleep/exercise modes | Mobi (X2: bolus only) |

The "Read from the pump" step is the one that makes Sukrara a real pump app. A DIY loop is **not** on this list: it
belongs in AndroidAPS, not in Sukrara. AAPS's Tandem driver would need the same connection slot, so Sukrara would
then go back to the follower side, or hand off the pumpX2 session the way controlX2 exports it to AAPS.

### The app's internals

1. **A pump driver beside the sensor driver.** Add a `PumpDriver` interface next to `SensorDriver`
   (`A/direct/SensorDriver.kt:25`). It needs:
   - a status flow and an incremental history cursor;
   - commands, in the "Command" step only;
   - implementations: pumpX2 (MIT, Java; it is a library, so the Kotlin-only rule still holds), a Tandem Source
     client and a CareLink client.
2. **A foreground service that owns the connection**, like controlX2's `CommService`:
   - type `connectedDevice`;
   - one owner, and nothing else opens BLE to the pump;
   - pairing through **CompanionDeviceManager**, which gives background start and presence events, with the
     Android 16 `ObservingDevicePresenceRequest` API
     ([guide](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing));
   - one more process for the battery investigation (11) to measure.
3. **Secrets.** The J-PAKE-derived secret is the credential: keep it Keystore-wrapped. It belongs in the backup,
   because losing it means re-pairing, and on the Mobi that needs the Qi charger.
4. **Storage.** Add `OwnerDb` tables beside `readings`:
   - `bolus`: requested/delivered U, kind normal | automatic | quick, carbs and BG entered, start/end;
   - `basal`: segments of scheduled | temp | automatic | suspended rate over time;
   - `pump_event`: cartridge, cannula fill, site change, battery, occlusion, alarms;
   - `pump_status`: reservoir, battery, pump IOB, mode, last contact.

   Key every row by `(pump serial, history sequence)`, so a re-sync is idempotent, as in AAPS's `InterfaceIDs`.
5. **Glucose from the pump** becomes a source: `Reading.dev` = `pump:<serial>`. Merge it with the G7 observer
   readings by timestamp, so a switch between them leaves no gap and no duplicates.
6. **IOB comes from the pump, not from us.** Show Control-IQ's own IOB, as controlX2 does
   (`ControlIQIOBRequest`). A second, different number calculated in Sukrara would be worse than none.
7. **Treatments: one source of truth.**
   - Pump boluses arrive from history, with origin `pump`, and are read-only in Sukrara.
   - The manual *rapid* field stays only for pen injections (backup, pump off). The dialog says so when a pump is
     connected, so the same insulin isn't counted twice.
   - Carbs entered in the pump's bolus calculator come in as carbs.
8. **Alerts** (spec + Android + Mac, with new timeline scenarios, under the alert-rule rule).
   - New kinds:
     - pump unreachable: no contact for N min, as in AAPS `checkPumpUnreachableAlarm`;
     - low reservoir and low battery;
     - occlusion / delivery stopped;
     - automation off.
   - The pump already vibrates for its own alarms. The open question is whether the phone repeats them or only
     mirrors them to followers and the Mac.
   - `muteAfterInsulin` must decide whether Control-IQ's automatic boluses count. The user approves those
     expectations.
9. **Protocols.** OGS / Nostr / BLE need these record kinds, landing with both implementations so the Mac and
   followers see them:
   - pump boluses: `insulin.how=pump`, already allowed, plus a subtype;
   - `basal` segments;
   - pump status in `device`;
   - pump events.
10. **Uploads.**
    - **Nightscout:**
      - treatments: `Meal`/`Correction Bolus`, automatic as `type:"automated"`;
      - `Temp Basal`, `Site Change`, `Insulin Change`, `Pump Battery Change`;
      - `devicestatus.pump {clock, reservoir, battery, status}` + IOB.
    - **Tidepool:** switch from injected `insulin` to `bolus` / `basal` / `deviceEvent` / `pumpSettings`.
    - **LibreView:** boluses as `insulinEntries` only.
    - **Local xDrip+ server:** report the real IOB, so the Garmin faces from [08](08-garmin.md) show it.
11. **Testing without a pump.**
    - A replay harness over pumpX2's own message test vectors, as [23](23-sensors.md) did with sensor traces.
    - pumpX2 has a pump simulator for development.
    - DebugReceiver fakes for pump state go through `DebugFakes.run`: debug builds only, never on a release.

### UI/UX

Visual language as before: actions are pills, red only for errors, no pointless detail.

- **Home (`GlucosePanel`, `A/ui/Widgets.kt:136`).**
  - A **pump strip** under the value card: `IOB 2.4 U · 112 U · 80 % · 2 min`.
  - The automation state as a pill: *Automatic*, *Sleep*, *Exercise*, *Off*, *Suspended*.
  - Tapping it opens the pump screen. Cartridge age and site age reuse the sensor-life ring
    (`A/graph/SensorBadge`).
- **Graph (Android + Mac + fixtures, under the one-graph rule).**
  - A **basal lane** under the curve: scheduled as a line, delivered as a fill, suspend as a gap, as in AAPS
    `GraphData.kt:82-98`.
  - Bolus marks split into manual and automatic: small marks for automatic, like AAPS's SMB points.
  - Optional IOB curve.
- **Pump screen:**
  - status, model/serial/firmware, last contact;
  - today's totals (bolus, basal, TDD);
  - history: boluses, basal changes, alarms;
  - in the "Command" step, mode controls: sleep/exercise, suspend/resume.
- **Settings.**
  - An **Insulin pump** group next to *Glucose source*, with a pairing flow modelled on the new-sensor screen
    ([21](21-new-sensor-flow.md)):
    1. "log out of the Tandem app" (frees the slot);
    2. the CompanionDeviceManager chooser;
    3. the 6-digit code shown on the pump;
    4. connected.
  - *Glucose source* gains **From the pump**.
  - The cloud step adds Tandem Source / CareLink to *Apps*, next to LibreView.
- **Everywhere else:**
  - an IOB slot in the value-card slot system (widgets, lock screen, notification);
  - reservoir and IOB in the Mac menubar dropdown;
  - IOB and pump status for followers.
  - **Commands stay on the wearer's phone (and maybe their watch). Never from the Mac or a follower.**
- **Bolus (the "Command" step).** The flow:
  1. the amount, with the pump's own calculator suggestion (`BolusCalcDataSnapshotRequest`), so Sukrara adds no
     dosing algorithm;
  2. a confirmation sheet showing amount, IOB and glucose, with an extra warning when glucose is low or falling;
  3. **BiometricPrompt**, as Trio requires Face ID;
  4. progress with **Cancel**.

  Guards:
  - a cap at the pump's max bolus;
  - a double-tap guard;
  - AAPS's duplicate check: refuse when a newer bolus exists than the one the screen showed.

  The pump beeps on each one, which is fine for manual boluses.

### Order of work

Do **A** first if the user starts on the official app. It is small, works for any of the three pumps, and fills the
data model, graph and uploads that B and C reuse. Then **B** as a standalone spike, as the rules require. **C**
stays a separate decision.

## Questions for Trueta / Novalab

1. Is the Mobi definitely available to you through ICS now, and is it supplied with the G7? Does Trueta support
   patients who run the Mobi from an iPhone they own?
2. When does Libre 3 Plus come to the t:slim X2 in Spain, and when do Abbott sensors come to the Mobi?
3. Is a parallel Libre 3 Plus funded or self-paid?
4. For the 780G: is Instinct the default sensor for new patients (Trueta already supplies it), and does the Flex
   upgrade programme include Spain?
5. Pump warranty and replacement term under the ICS contract (typically 4 years): is it a lock-in until then?

## Not verified

- Which pump + sensor combinations are listed in the ICS framework agreement.
- Whether the t:slim mobile app (and its mobile bolus) is available in Spain.
- Whether the Dexcom G7 and Tandem apps refuse rooted phones.
- Whether Control-IQ+ added user-set targets.
- The Mobi Tubeless clearance status after August 2026.
