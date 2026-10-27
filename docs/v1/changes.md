# Plan amendments (orchestrator; override PLAN.md and briefs)

> Record of the Sukrara V1 night run (2026-10-27), copied from the run's working file `plan/CHANGES.md` with paths
> made readable. `OPS/…` paths name the run's scratch files (briefs, reviews, issue lists), which are not in
> the repo. Index: [README.md](README.md).

Source: `OPS/reviews/plan-critic-product.md` (read it for details) and `OPS/reviews/plan-critic-arch.md` (when present).

## From the product/safety critique (all accepted)
- P1. **LibreView receiver ID is P0** (briefs 12, 18): V1 must start/take over sensors with the LibreView account
  receiver ID like the PoC (LibreView sign-in to obtain it is P0), so the owner can take over today's sensor and
  move back to the PoC if V1 fails. No random own receiver ID as the default.
- P2. **Restore followers only after the sensor runs in V1** (brief 24): setup order = sensor takeover confirmed →
  then identity restore/follower rotation, with a gate and a plain warning. README "switching from the showcase"
  steps are ordered and numbered. Feature test: V1 Mac follows a 0.11.53 wearer (protocol compat).
- P3. **No double or phantom alarms** (24, 16, 11, 21, 01/install): a "Turn off the showcase" setup step + home
  banner while the PoC package is installed; `tools/install.sh mac` quits the PoC and removes its login item; before
  the first reading show "Not monitoring: no sensor yet" (no no-data alarm until monitoring has started).
- P4. **Alert thresholds explicitly confirmed** during setup (17/24), never silently defaulted.
- P5. **Setup permission checklist = the reliability row** (24, 16): notifications, full-screen intent, exact alarm,
  battery exemption, DND override / channel importance, Bluetooth.
- P6. **Release notes get a "Not in 0.90.0" table** (27 / release): root status bar, LSPosed lock widget, Garmin
  via xDrip+ server, uploads, reminders, anything else cut. Live Update chip = P0 acceptance item (10/16).
- P7. **Sensor-end warning notification is P0** (minimal form) (18/11).
- P8. **Owner "first hour" check walk** in the release notes (27 / release).
- P9. **Widget and notification tap → quick Add treatment** stays as in the PoC (20, 10, 19).
- P10. **"Start fresh" and the role choice are confirmed**, each saying what it means (24).

## From the architecture/feasibility critique (accepted unless noted)
- A1. **Re-tier**: widgets 20 (Android) and 22 (Mac) → P1 (run on free slots; 0.90.x follow-up RC if not in 0.90.0);
  28 cut (→ issue); 17 and 23 shrink to what daily use needs. Orchestrator re-tiers again at **06:30**. The PoC stays
  installed as the fallback. 0.90.0 ships what passes; later RCs (0.90.1+) add the rest.
- A2. **Scaffolds split**: 01a / 02a / 03a = the minimal skeleton others build on, committed within ~30 min and
  announced with heartbeat `MILESTONE <NN>a <sha>`; the orchestrator merges that commit into `next`. 02a proves
  KSP + Room compile under the chosen AGP. 01b / 02b / 03b continue on the same branch afterwards.
- A3. **Restore gate** (24, 13): restore/rotation requires the showcase uninstalled or its sharing turned off (check
  the package / ask explicitly). 13 detects "another app is publishing as this identity" (newer roster/backup from a
  different device) and shows a blocking state instead of republishing back and forth. Add a sync fixture for it.
- A4. **DI**: no shared `AppGraph.kt` regions. Each feature chunk owns one module class (e.g. `AlertsModule`) that
  `AppGraph` composes; only 02 edits `AppGraph` structure.
- A5. **`:core:protocol` is pure JVM with no nostr-sdk**; SDK-backed crypto lives in `:share` behind interfaces.
  The PoC's SDK is `org.nostrdevkit:nostr-sdk:0.45.1` (brief 06 named the wrong artifact).
- A6. **DAG fixes**: `:core:designsystem` moves to chunk 08 (needed by 16/18/24). 24, 21, 23 code against
  interfaces instead of waiting for merges. PLAN §6 is authoritative over brief "depends on" lines where they differ
  (planner reconciles).
- A7. **27 split**: 27a release smoke (R8, JNA, native core, new key) at ~06:30; 27b short first-run walk late.
- A8. **Gates**: G0 critic subagent is mandatory for safety chunks (alerts 04/05/11, sensor 12/18, protocol/share
  06/07/13/15, monitor 10, release 27); for other chunks a self-critique pass is enough. G2 code reviewer stays for
  every chunk. UX review is batched by the orchestrator per wave (one UX reviewer over several chunks' screenshots).
  Roborazzi matrix: light+dark, default + largest font, one phone size.
- A9. **Mac widget fallback** = the PoC's proven `swiftc` path (not xcodegen). A documented Swift 5-mode escape is
  allowed for the app target only if Swift 6 blocks; core packages stay Swift 6.
- A10. **`:core:model` is additive-only** and holds `GlucoseState` + the `GlucoseHub` interface, so features and
  widgets don't depend on `:monitor`.
