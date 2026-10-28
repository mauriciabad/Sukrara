// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

import java.time.Instant
import java.time.ZoneId

/**
 * The time source of all logic (inject it; never call `Instant.now()` in engines), so tests run on virtual time.
 * [zone] is read every time it is needed: the person travels and DST changes while the app runs.
 */
interface Clock {
    fun now(): Instant

    val zone: ZoneId
}

/** The device clock and its current time zone. */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now()

    override val zone: ZoneId get() = ZoneId.systemDefault()
}
