# DAG v2 (authoritative; brief "depends on" lines must match exactly). "iface" = code against the interface from 02a / core:model, don't wait for merge.

> Record of the Sukrara V1 night run (2026-10-27), copied from the run's working file `plan/DAG-v2.md` with paths
> made readable. `OPS/…` paths name the run's scratch files (briefs, reviews, issue lists), which are not in
> the repo. Index: [README.md](README.md).

| # | id | P | eff | G0 critic | depends on |
|---|---|---|---|---|---|
| 01a/01b | foundation | P0 | H | – | r0 (01a skeleton ~30 min → MILESTONE) |
| 02a/02b | android-scaffold | P0 | H | – | r0 (02a: modules + core:model GlucoseState/GlucoseHub + KSP/Room compile proof) |
| 03a/03b | mac-scaffold | P0 | H | – | r0 |
| 04 | android-alerts-core | P0 | H | yes | 01a, 02a (pre) |
| 05 | mac-alerts-core | P0 | H | yes | 01a, 03a (pre) |
| 06 | android-protocol | P0 | H | yes | 01a, 02a (pre) |
| 07 | mac-protocol | P0 | H | yes | 01a, 03a (pre) |
| 08 | android-graph-designsystem | P0 | M | self | 01a, 02a (pre) |
| 09 | android-data | P0 | M | self | 01a, 02a |
| 10 | android-monitor | P0 | H | yes | 02a; 09 iface |
| 11 | android-alerts-runtime | P0 | H | yes | 04; 09, 10 iface |
| 12 | android-sensor-libre3 (+ LibreView receiver-ID client) | P0 | H | yes | 02a; 09 iface |
| 13 | android-share-owner (+ identity-conflict state) | P0 | H | yes | 06; 09 iface |
| 14 | mac-core-graph | P0 | M | self | 01a, 03a |
| 15 | mac-follower-runtime | P0 | H | yes | 07; 14 iface |
| 16 | android-home-ui | P0 | M | self | 08; 09, 10 iface |
| 17 | android-settings-ui (shrunk) | P0 | M | self | 08; 09, 11 iface |
| 18 | android-sensor-ui (+ LibreView sign-in, sensor-end warning) | P0 | H | yes | 08, 12 |
| 19 | android-treatments (+ pens P1 part) | P0 | M | self | 08, 09; 13 iface |
| 21 | mac-shell-alerts | P0 | H | yes | 05, 14; 15 iface |
| 23 | mac-windows (shrunk) | P0 | M | self | 14; 15 iface |
| 24 | android-setup-sharing | P0 | M-H | yes | 08; 09, 13, 18 iface |
| 27a | android-release-smoke | P0 | H | yes | 01b, 02b, 12 (start ~06:30) |
| 27b | android-first-run-walk | P0 | H | yes | 11, 16, 18, 24 merged (~07:15) |
| 20 | android-widgets | P1 | M | self | 08; 10 iface; 19 (quick Add treatment) |
| 22 | mac-widget | P1 | M | self | 14, 15 |
| 25 | android-share-follower-ble | P1 | H | yes | 06, 13 |
| 26 | android-integrations (uploads etc.; LibreView *upload* only) | P1 | M | self | 09; 12 (LibreView client) |
| 28 | android-systemui | CUT → issue | | | |
Orchestrator re-tiers at 06:30. UX review batched per wave by the orchestrator (not inside chunks).
Roborazzi matrix: light+dark × default+largest font, one phone size.
DI (A4): each chunk owns one `XxxModule` class in its module; only 02 edits `AppGraph` structure.
