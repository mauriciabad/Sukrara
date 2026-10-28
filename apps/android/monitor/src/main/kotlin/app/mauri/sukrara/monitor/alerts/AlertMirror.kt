// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings

/**
 * The settings a follower's alerts run with. When it takes its alert settings from the wearer (the wearer's settings
 * arrive through the mirrored-settings flow; null until then or when not following), the wearer's rules and
 * quiet windows apply; what belongs to this phone stays its own: alerts on/off, the sounds, the alarm volume per kind,
 * the notification actions and where alerts come from.
 */
object AlertMirror {
    fun effective(own: AlertSettings, wearer: AlertSettings?): AlertSettings {
        if (wearer == null) return own
        return wearer.copy(
            on = own.on,
            rules = AlertKind.entries.associateWith { k -> wearer.rule(k).copy(alarm = own.rule(k).alarm) },
            sounds = own.sounds,
            actions = own.actions,
            source = own.source,
        )
    }
}
