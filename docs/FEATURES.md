# Sukrara behaviour catalog

The features and exact behaviours Sukrara has, written so the final app (rebuilt from scratch) keeps every one of
them. One line per behaviour, with the rule and the numbers; the "why" and the history stay in the investigation
reports (`docs/NN-*.md`) and `docs/10-showcase.md`.

Rule: every change that adds or changes a behaviour updates this file in the same commit.

## Alerts

## Treatments

- Amounts are written lowercase with no space before the unit everywhere in the app: `4.5u`, `18u`, `30g` (dialog suffixes, lists, chart labels, the slot; also the Nightscout API's long-acting note).
- Short durations read `now` (< 1 min), `12m`, `1h 5m` (whole hours: `3h`), only hours from 6 h (`7h`), days from 48 h (`2d`); time left rounds up (`1m` until it is over).
- The home treatments list (wearer) and the follower's list end each row with the time since the entry (`12m`, `3h`), refreshed every 30 s.
- The treatment dialog focuses carbs instead of rapid insulin when the latest fresh reading is below an enabled low-side alert threshold (`AlertStatus.level` in `Kind.LOW_SIDE`); otherwise rapid.

## Graph and value card

- Treatment marks on every chart (app, widgets, notification, lock screen) are labelled with the amount and the time since it: `4.5u 12m`; entries ≤ 3 min apart (a meal) show the time once; a label that doesn't fit with its time is placed without it, then dropped.
- From each rapid / carbs mark a 2 dp line in its colour runs along the treatment lane to where its alert mute ends (`AlertStatus.muteUntil`: rapid + insulin mute, carbs + carbs mute); insulin's line sits 1.5 dp above the lane centre, carbs' 1.5 dp below. Ended: a vertical tick at the end. Still running: the line reaches the chart's right edge with a chevron. Long-acting, notes and kinds whose mute is off get no line.
- Status badge under the value (app panel, value/combined widgets, lock screen value) from `AlertStatus`: None → nothing; Needed → filled pill in the colour of what to log with its marker: `Eat carbs` (low side), `Take insulin` (high side, extremely high included); Taken → the same pill tonal (colour at ~24 % dark / 18 % light, text in the colour) with the "notifications paused" bell and the time left of the mute (`9m left`).
- The badge needs the value box to keep ≥ 26 dp of number after a row of 14–24 dp; otherwise it becomes an icon-only circle beside the arrow when there is room, else it is left out. A late reading's time sits after the badge on its row.
- Widgets redraw when the alert settings change (badge, mute lines and slot depend on them); the widget's description for screen readers includes the badge's words.
- Last-treatment slot beside the value (on by default, *Next to the number* = leftmost; *At the right end*; *Off*): kind marker, amount and time since, inside a ring in the kind's colour for the share of the alert mute left (shrinking towards 12 o'clock); empty grey track once the mute is over or when the kind's mute is off.
- The slot shows the treatment that mutes the current alert, else the newest rapid insulin (kept 4 h) or carbs (kept 3 h); a meal logged at the same minute shows the insulin. Long-acting insulin and notes never show.
- Without a recent treatment the slot shows its fallback (default: time in range, 30 days, faded 55 %; any slot kind and period, or nothing), only where the slot's data covers ≥ 25 % of its window.
- Slot priority when room is short: main ring, recent treatment, second ring, fallback, third ring. The app panel fits `(width − 188 dp) / 76 dp` slots (1–3); widgets fit as before (≤ 4).

## Settings

## Live notification

## Lock screen widget
