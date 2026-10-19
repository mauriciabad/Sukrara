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

- **No option keeps the Libre 3 Plus in Spain today.** Both Tandem pumps come with a **Dexcom G7**. The 780G comes
  with **Guardian 4, Simplera Sync or Instinct**. Instinct is made by Abbott on Libre 3 Plus technology, but it is
  locked to the pump.
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
| Sensor in Spain | **Dexcom G7 only**; Abbott "expected in select countries in 2027" | **Dexcom G7** ([since 2024](https://www.anisalud.com/actualidad/notas-de-prensa-anis/10747-una-gran-alianza-dexcom-g7-se-conecta-a-tandem-t-slim-x2)); Libre 3 Plus live in UK/CH/SE/FI/IT since 2026-07, **Spain not listed** ([Medical Economics](https://www.medicaleconomics.com/view/tandem-s-t-slim-x2-insulin-pump-now-compatible-with-abbott-s-freestyle-libre-3-plus-cgm-in-five-countries)) | Guardian 4, Simplera Sync, **Instinct** (Abbott, 15 days, [EU launch 2026-06-23](https://news.minimed.com/2026-06-23-MiniMed-launches-Instinct-sensors-in-Europe-giving-people-with-diabetes-more-sensor-choice-with-the-same-proven-outcomes), Spain among the first, [Canal Diabetes](https://canaldiabetes.com/minimed-sensores-instinct-instinct-go-diabetes/)) |
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
off the table.

## Questions for Trueta / Novalab

1. Is the Mobi definitely available to you through ICS now, and is it supplied with the G7? Does Trueta support
   patients who run the Mobi from an iPhone they own?
2. When does Libre 3 Plus come to the t:slim X2 in Spain, and when do Abbott sensors come to the Mobi?
3. Is a parallel Libre 3 Plus funded or self-paid?
4. For the 780G: which sensor does ICS supply (Simplera or Instinct), and does the Flex upgrade programme include
   Spain?
5. Pump warranty and replacement term under the ICS contract (typically 4 years): is it a lock-in until then?

## Not verified

- Which pump + sensor combinations are listed in the ICS framework agreement.
- Whether the t:slim mobile app (and its mobile bolus) is available in Spain.
- Whether the Dexcom G7 and Tandem apps refuse rooted phones.
- Whether Control-IQ+ added user-set targets.
- The Mobi Tubeless clearance status after August 2026.
