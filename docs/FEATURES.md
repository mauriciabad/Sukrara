# Sukrara behaviour catalog

The features and exact behaviours Sukrara has, written so the final app (rebuilt from scratch) keeps every one of
them. One line per behaviour, with the rule and the numbers; the "why" and the history stay in the investigation
reports (`docs/NN-*.md`) and `docs/10-showcase.md`.

Rule: every change that adds or changes a behaviour updates this file in the same commit.

## Alerts

## Treatments

## Graph and value card

## Settings

- Settings pages are AOSP SettingsLib SPA (android17-release, vendored as `settingslib-spa`): SettingsTheme, SettingsScaffold, Category, Preference / SwitchPreference / TwoTargetSwitchPreference / MainSwitchPreference / CheckboxPreference, Footer, SettingsBanner, HighlightBox.
- A page opens with the large title (36 dp) expanded; scrolling collapses it into the 22 dp title of the top bar; the round tonal back button stays.
- Groups are 20 dp rounded cards (4 dp inner corners) 16 dp from the screen edges, 2 dp between rows, surfaceBright on a surfaceContainer page; group titles in primary.
- Rows: 72 dp min height, centred vertically, title 16 sp medium (500), summary 14 sp onSurfaceVariant, icons 24 dp in a 40 dp box tinted onSurfaceVariant; plain rows show no chevron.
- A row that both toggles and opens something (two targets) shows › then a 1 × 40 dp line, then the switch; switches show a check (on) / close (off) thumb icon.
- Overview category icons: the 24 dp glyph in the hue's tone 30 on the device's adaptive-icon shape (40 dp) in tone 90 (dark theme: 80), AOSP homepage colours.
- Page intros are plain body-large text (TopIntroPreference), no illustration.
- Choice dialogs are SPA ListPreference dialogs: choosing saves and closes.
- Every list of numbers ends with "Custom": it reveals a number field (unit shown, range under it); OK saves only a value in range; a custom value shows as "Custom" selected with its value, and round-trips through settings export / import.
- Custom ranges: Nostr interval 1–120 min, LibreView interval 1–240 min, alert reminders 1–240 min, thresholds 40–400 mg/dL, rates 0.5–10 mg/dL/min (one decimal), no data 5–240 min, quiet after insulin 10–360 min, falling / carbs 5–120 min, graph windows 1–48 h, ring periods 1–90 days.
- Alerts page: main switch, then one two-target row per alert (switch = on/off, row = the alert's page), quiet rules, sounds.
- Each alert has its own page: main switch "Use <alert> alert", Alert when (threshold, remind), Sound (sound, volume), Test (play sound, Play with vibration, send a test alert, notification category). Followers mirroring the wearer see threshold and reminders read-only ("Set by the wearer's app").
- Every ▶ (sound pack list, an alert's sound list, Play sound) also plays that alert's vibration (AlertHaptics, alarm or notification usage) unless "Play with vibration" is unticked; the check box is a per-phone UI preference, on by default, and only affects previews.

## Live notification

## Lock screen widget
