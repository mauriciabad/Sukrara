// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings

/**
 * The settings a follower's alerts run with: [mirrored] = the values *Settings from* chooses for the mirrored rows (the
 * wearer's or the defaults; null = Custom, this phone's own). Only the schema's mirrored rows come from it (each kind's
 * on / threshold / repeat but no data's, the quiet-after rules); what belongs to this phone stays its own: alerts
 * on/off, no data, the sounds, the alarm volume per kind, the notification actions and where alerts come from — so a
 * wearer's payload can never silence this phone's no-data alarm.
 */
object AlertMirror {
    fun effective(own: AlertSettings, mirrored: AlertSettings?): AlertSettings {
        if (mirrored == null) return own
        return mirrored.copy(
            on = own.on,
            rules =
                AlertKind.entries.associateWith { k ->
                    if (k == AlertKind.NO_DATA) own.rule(k) else mirrored.rule(k).copy(alarm = own.rule(k).alarm)
                },
            sounds = own.sounds,
            actions = own.actions,
            source = own.source,
        )
    }
}
