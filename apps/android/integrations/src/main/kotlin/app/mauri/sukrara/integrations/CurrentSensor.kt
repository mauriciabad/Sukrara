// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations

import java.time.Instant

/** The wearer's current sensor as the integrations need it: its serial and life ([start], [end]). */
data class CurrentSensor(
    val serial: String,
    val start: Instant,
    val end: Instant,
)
