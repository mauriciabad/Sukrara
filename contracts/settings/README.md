# Settings schema (V1)

[`settings.json`](settings.json) is THE settings schema of Sukrara V1 (its `_about` explains every field).
`python3 tools/settings/gen.py` turns it into `SettingsSpec.kt` (`:core:data`) and `SettingsSpec.swift`
(`SukraraCore`): number formats, option lists, the inventory, and the store / key constants. App code takes every
stored key from the generated `SettingsSpec` (tools/check rule `settings` fails on a key written by hand).
[`durations.json`](durations.json) holds the duration formatting cases both `Durations` formatters pass.

## Stores

- **Android:** one Preferences DataStore file per area: `settings` (general), `graph`, `alerts`, `sensor`, `sharing`,
  `integrations`, `widgets`, `pens`, `statusbar`, `lock`. Complex values (lists, per-place maps) are JSON strings
  under their key.
- **Mac:** `defaults` = the app's UserDefaults (App Group suite where the widget reads it), `system` = macOS state
  (`SMAppService.mainApp` for Open at login).
- **Alerts:** ONE key `alerts` per platform holding the JSON object of [`../alerts/SPEC.md`](../alerts/SPEC.md) §3.
  The schema's `alerts.*` entries address its fields with `path` (`{kind}` = each kind). Safety rule for both apps: if
  the stored value can't be decoded, the app keeps running with the **last value it decoded** (in memory, else the
  SPEC defaults with alerts ON), shows a visible "Alert settings couldn't be read" error with a way to reset them,
  and never turns alerts off or drops a threshold silently. Each app's alert-settings store has a test for it.

## PoC → V1 keys (for humans; no code reads this table)

V1 reads nothing from the PoC (owner decision: no migration). The table says where each setting went.
Cut settings are hardcoded in V1 at the value the PoC used by default.

| setting | PoC Android | PoC Mac | V1 Android | V1 Mac | note |
|---|---|---|---|---|---|
| `graph.band.veryLow` | `graph:veryLow` | `defaults:bandVeryLow` | `graph:bandVeryLow` | `defaults:bandVeryLow` |  |
| `graph.band.low` | `graph:low` | `defaults:bandLow` | `graph:bandLow` | `defaults:bandLow` |  |
| `graph.band.tight` | `graph:tight` | `defaults:bandTight` | `graph:bandTight` | `defaults:bandTight` |  |
| `graph.band.high` | `graph:high` | `defaults:bandHigh` | `graph:bandHigh` | `defaults:bandHigh` |  |
| `graph.band.veryHigh` | `graph:veryHigh` | `defaults:bandVeryHigh` | `graph:bandVeryHigh` | `defaults:bandVeryHigh` |  |
| `graph.band.extremelyHigh` | `graph:extremelyHigh` | `defaults:bandExtremelyHigh` | `graph:bandExtremelyHigh` | `defaults:bandExtremelyHigh` |  |
| `graph.hours` | `graph:defaultHours` | `defaults:graphHours` | `graph:graphHours` | `defaults:graphHours` |  |
| `graph.hours.place` | `graph:placeHours` | `defaults:menubarHours,widgetHours` | `graph:placeHours` | `defaults:menubarHours,widgetHours` |  |
| `graph.ringCount` | `graph:ringCount` | `defaults:ringCount` | `graph:ringCount` | `defaults:ringCount` |  |
| `graph.slots` | `graph:slots` | `defaults:slots` | `graph:slots` | `defaults:slots` |  |
| `graph.ringRange` | `graph:ringRange` | `defaults:ringRange` | `graph:ringRange` | `defaults:ringRange` |  |
| `graph.ringOrder` | `graph:ringOrder` | `defaults:ringOrder` | `graph:ringOrder` | `defaults:ringOrder` |  |
| `graph.ringMarks` | `graph:ringMarks` | `defaults:ringMarks` | `graph:ringMarks` | `defaults:ringMarks` |  |
| `graph.side` | `graph:side` | `defaults:side` | `graph:side` | `defaults:side` |  |
| `graph.marks` | `graph:marks` | `defaults:marks` | `graph:marks` | `defaults:marks` |  |
| `graph.columnCrown` | `graph:columnCrown` | `defaults:columnCrown` | `graph:columnCrown` | `defaults:columnCrown` |  |
| `graph.lines` | `graph:lines` | `defaults:lines` | `graph:lines` | `defaults:lines` |  |
| `graph.gridHours` | `graph:gridHours` | `defaults:gridHours` | `graph:gridHours` | `defaults:gridHours` |  |
| `graph.lastPlace` | `graph:lastPlace` | `defaults:lastPlace` | `graph:lastPlace` | `defaults:lastPlace` |  |
| `graph.lastElse` | `graph:lastElse` | `defaults:lastElse` | `graph:lastElse` | `defaults:lastElse` |  |
| `graph.sensorTime.place` | `graph:sensorHidden` | `defaults:menubarSensorTime,widgetSensorTime` | `graph:sensorTimeHidden` | `defaults:menubarSensorTime,widgetSensorTime` |  |
| `sensor.countdownHours` | `sensorlife:countdown_hours` | `defaults:countdownHours` | `sensor:countdownHours` | `defaults:countdownHours` |  |
| `follower.settingsFrom` | `wearer-settings:settingsFrom` | `defaults:settingsFrom` | `sharing:settingsFrom` | `defaults:settingsFrom` |  |
| `alerts.on` | `alerts:on` | `alerts:on` | `alerts:alerts · on` | `defaults:alerts · on` |  |
| `alerts.kind.on` | `alerts:alerts.{kind}.on` | `alerts:alerts.{kind}.on` | `alerts:alerts · alerts.{kind}.on` | `defaults:alerts · alerts.{kind}.on` |  |
| `alerts.threshold.very_low` | `alerts:alerts.very_low.mgdl` | `alerts:alerts.very_low.mgdl` | `alerts:alerts · alerts.very_low.mgdl` | `defaults:alerts · alerts.very_low.mgdl` |  |
| `alerts.threshold.low` | `alerts:alerts.low.mgdl` | `alerts:alerts.low.mgdl` | `alerts:alerts · alerts.low.mgdl` | `defaults:alerts · alerts.low.mgdl` |  |
| `alerts.threshold.high` | `alerts:alerts.high.mgdl` | `alerts:alerts.high.mgdl` | `alerts:alerts · alerts.high.mgdl` | `defaults:alerts · alerts.high.mgdl` |  |
| `alerts.threshold.very_high` | `alerts:alerts.very_high.mgdl` | `alerts:alerts.very_high.mgdl` | `alerts:alerts · alerts.very_high.mgdl` | `defaults:alerts · alerts.very_high.mgdl` |  |
| `alerts.threshold.extremely_high` | `alerts:alerts.extremely_high.mgdl` | `alerts:alerts.extremely_high.mgdl` | `alerts:alerts · alerts.extremely_high.mgdl` | `defaults:alerts · alerts.extremely_high.mgdl` |  |
| `alerts.threshold.falling_fast` | `alerts:alerts.falling_fast.rate` | `alerts:alerts.falling_fast.rate` | `alerts:alerts · alerts.falling_fast.rate` | `defaults:alerts · alerts.falling_fast.rate` |  |
| `alerts.threshold.rising_fast` | `alerts:alerts.rising_fast.rate` | `alerts:alerts.rising_fast.rate` | `alerts:alerts · alerts.rising_fast.rate` | `defaults:alerts · alerts.rising_fast.rate` |  |
| `alerts.threshold.no_data` | `alerts:alerts.no_data.min` | `alerts:alerts.no_data.min` | `alerts:alerts · alerts.no_data.min` | `defaults:alerts · alerts.no_data.min` |  |
| `alerts.kind.repeat` | `alerts:alerts.{kind}.repeatMin` | `alerts:alerts.{kind}.repeatMin` | `alerts:alerts · alerts.{kind}.repeatMin` | `defaults:alerts · alerts.{kind}.repeatMin` |  |
| `alerts.kind.alarm` | `alerts:alerts.{kind}.alarm` | `alerts:alerts.{kind}.alarm` | `alerts:alerts · alerts.{kind}.alarm` | `defaults:alerts · alerts.{kind}.alarm` |  |
| `alerts.kind.sound` | `alerts:sounds.kinds.{kind}` | `alerts:sounds.kinds.{kind}` | `alerts:alerts · sounds.kinds.{kind}` | `defaults:alerts · sounds.kinds.{kind}` |  |
| `alerts.muteAfterInsulin.on` | `alerts:muteAfterInsulin.on` | `alerts:muteAfterInsulin.on` | `alerts:alerts · muteAfterInsulin.on` | `defaults:alerts · muteAfterInsulin.on` |  |
| `alerts.muteAfterInsulin.min` | `alerts:muteAfterInsulin.min` | `alerts:muteAfterInsulin.min` | `alerts:alerts · muteAfterInsulin.min` | `defaults:alerts · muteAfterInsulin.min` |  |
| `alerts.muteFallingHigh.on` | `alerts:muteFallingHigh.on` | `alerts:muteFallingHigh.on` | `alerts:alerts · muteFallingHigh.on` | `defaults:alerts · muteFallingHigh.on` |  |
| `alerts.muteFallingHigh.min` | `alerts:muteFallingHigh.min` | `alerts:muteFallingHigh.min` | `alerts:alerts · muteFallingHigh.min` | `defaults:alerts · muteFallingHigh.min` |  |
| `alerts.muteAfterCarbs.on` | `alerts:muteAfterCarbs.on` | `alerts:muteAfterCarbs.on` | `alerts:alerts · muteAfterCarbs.on` | `defaults:alerts · muteAfterCarbs.on` |  |
| `alerts.muteAfterCarbs.min` | `alerts:muteAfterCarbs.min` | `alerts:muteAfterCarbs.min` | `alerts:alerts · muteAfterCarbs.min` | `defaults:alerts · muteAfterCarbs.min` |  |
| `alerts.actions.muteMin` | `alerts:notificationActions.muteMin` | `alerts:notificationActions.muteMin` | `alerts:alerts · notificationActions.muteMin` | `defaults:alerts · notificationActions.muteMin` |  |
| `alerts.actions.relaxMin` | `alerts:notificationActions.relaxMin` | `alerts:notificationActions.relaxMin` | `alerts:alerts · notificationActions.relaxMin` | `defaults:alerts · notificationActions.relaxMin` |  |
| `alerts.actions.backMgdl` | `alerts:notificationActions.backMgdl` | `alerts:notificationActions.backMgdl` | `alerts:alerts · notificationActions.backMgdl` | `defaults:alerts · notificationActions.backMgdl` |  |
| `alerts.actions.backMin` | `alerts:notificationActions.backMin` | `alerts:notificationActions.backMin` | `alerts:alerts · notificationActions.backMin` | `defaults:alerts · notificationActions.backMin` |  |
| `alerts.sounds.pack` | `alerts:sounds.pack` | `alerts:sounds.pack` | `alerts:alerts · sounds.pack` | `defaults:alerts · sounds.pack` |  |
| `alerts.sounds.shuffle` | `alerts:sounds.shuffle` | `alerts:sounds.shuffle` | `alerts:alerts · sounds.shuffle` | `defaults:alerts · sounds.shuffle` |  |
| `alerts.source` | `alerts:source` | `alerts:source` | `alerts:alerts · source` | `defaults:alerts · source` |  |
| `alerts.v` | `alerts:v` | `alerts:v` | `alerts:alerts · v` | `defaults:alerts · v` |  |
| `general.openAtLogin` |  | `system:SMAppService.mainApp` |  | `system:SMAppService.mainApp` |  |
| `report.days` |  | `defaults:reportDays` |  | `defaults:reportDays` |  |
| `source.source` | `features:source` |  | `settings:source` |  |  |
| `sharing.nostr` | `features:nostr` |  | `sharing:relaySharing` |  |  |
| `sharing.nostrInterval` | `features:nostrInterval` |  | — (cut) | — (cut) | battery-lab knob (PLAN §5 P2): V1 publishes every reading (PoC default 0 = every reading) |
| `sharing.bleFollowers` | `features:bleFollowers` |  | `sharing:nearbySharing` |  |  |
| `statusbar.chip` | `features:chip` |  | `statusbar:statusChip` |  |  |
| `statusbar.icon` | `features:icon` |  | `statusbar:statusIcon` |  |  |
| `statusbar.systemIcons` | `features:systemIcons` |  | `statusbar:statusSystemIcons` |  |  |
| `statusbar.hook` | `features:hook` |  | `statusbar:statusHook` |  |  |
| `statusbar.hookPosition` | `features:hookPosition` |  | `statusbar:statusHookPosition` |  |  |
| `statusbar.hookColor` | `features:hookColor` |  | `statusbar:statusHookColor` |  |  |
| `statusbar.hideNotifIcon` | `features:hideNotifIcon` |  | `statusbar:hideNotificationIcon` |  |  |
| `lock.widget` | `features:lockWidget` |  | `lock:lockWidget` |  |  |
| `lock.background` | `features:lockBg` |  | `lock:lockBackground` |  |  |
| `lock.appearance` | `features:lockLook` |  | `lock:lockAppearance` |  |  |
| `lock.treatmentTime` | `features:lockTreatmentTime` |  | `lock:lockTreatmentTime` |  |  |
| `advanced.screenOffSkip` | `features:screenOffSkip` |  | — (cut) | — (cut) | battery-lab knob (PLAN §5 P2): V1 always skips widget redraws while the screen is off (PoC default on) |
| `advanced.connPriority` | `features:connPriority` |  | — (cut) | — (cut) | battery-lab knob (PLAN §5 P2): V1 uses the PoC default, balanced priority |
| `apps.xdrip` | `features:garmin` |  | `integrations:xdripServer` |  |  |
| `apps.nightscout` | `features:nightscout` |  | `integrations:nightscoutServer` |  |  |
| `apps.nightscoutLan` | `features:nightscoutLan` |  | `integrations:nightscoutServerLan` |  |  |
| `apps.libreview` | `features:libreview` |  | `integrations:libreview` |  |  |
| `apps.libreviewInterval` | `features:libreviewInterval` |  | — (cut) | — (cut) | battery-lab knob (PLAN §5 P2): V1 uploads to LibreView every 5 min (PoC default) |
| `home.uploadBanner` | `features:uploadBanner` |  | `settings:uploadBanner` |  |  |
| `apps.tidepool` | `tidepool:settings` |  | `integrations:tidepool` |  |  |
| `apps.nightscoutUpload` | `nightscout-upload:settings` |  | `integrations:nightscoutUpload` |  |  |
| `apps.broadcasts` | `app-broadcasts:allowed` |  | `integrations:broadcastApps` |  |  |
| `sensor.reminders` | `sensorend:config` |  | `sensor:sensorReminders` |  |  |
| `widget.overrides` | `graph-widgets:hours,rings,ringRange,side,sensor` |  | `widgets:widgetHours,widgetRings,widgetRingRange,widgetSide,widgetSensor` |  |  |
| `pens.reading` | `features:penReading` |  | `pens:penReading` |  |  |
| `pens.primeBelow` | `features:penPrimeBelow` |  | `pens:penPrimeBelow` |  |  |
| `pens.cluster` | `features:penCluster` |  | `pens:penCluster` |  |  |
| `pens.primeBefore` | `features:penPrimeBefore` |  | `pens:penPrimeBefore` |  |  |
| `pens.pending` | `features:penPending` |  | `pens:penPending` |  |  |
| `pens.match` | `features:penMatch` |  | `pens:penMatch` |  |  |
| `pens.matchUnits` | `features:penMatchUnits` |  | `pens:penMatchUnits` |  |  |
| `pens.matchShare` | `features:penMatchShare` |  | `pens:penMatchShare` |  |  |
| `pens.offer` | `features:penOffer` |  | `pens:penOffer` |  |  |
| `pens.notify` | `features:penNotify` |  | `pens:penNotify` |  |  |
| `pens.pen` | `pens:pens` |  | `pens:pens` |  |  |
| `sensorCalendar.on` |  |  | `integrations:sensorCalendar` |  | new in V1 |
| `sensorCalendar.calendarId` |  |  | `integrations:sensorCalendarId` |  | new in V1 |
| `healthConnect.on` |  |  | `integrations:healthConnect` |  | new in V1 |
