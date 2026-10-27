// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import java.time.Duration
import java.time.Instant
import kotlin.math.sin

/** Synthetic glucose for tests and the screenshot gallery (never real data). */
object Samples {
    val NOW: Instant = Instant.parse("2026-01-15T12:00:00Z")

    /** A gentle wave over 12 h ending [ageMin] before [NOW] at [last] mg/dL. */
    fun readings(
        last: Int = 112,
        ageMin: Long = 1,
        source: SourceId = SourceId.Libre3,
    ): List<Reading> {
        val end = NOW.minus(Duration.ofMinutes(ageMin))
        return (0 until 12 * 60 step 5).map { i ->
            val t = end.minus(Duration.ofMinutes((12 * 60 - 5 - i).toLong()))
            val wave = (sin(i / 90.0) * 45).toInt()
            Reading(t, (last + wave - (sin((12 * 60 - 5) / 90.0) * 45).toInt()).coerceIn(40, 400), -1.2, source)
        }
    }

    fun wearer(
        last: Int = 112,
        ageMin: Long = 1,
        sensor: SensorState = SensorState.Active(NOW.plus(Duration.ofDays(9))),
    ): GlucoseState {
        val recent = readings(last, ageMin)
        return GlucoseState(
            role = Role.Wearer,
            latest = recent.last(),
            recent = recent,
            sensor = sensor,
            source = SourceStatus.Live(recent.last().t),
            alerts = app.mauri.sukrara.core.model.AlertUiState(),
            link = null,
            monitoringStartedAt = NOW.minus(Duration.ofDays(3)),
        )
    }

    fun noSensorYet(role: Role = Role.Wearer) =
        GlucoseState.Initial.copy(role = role, link = if (role == Role.Follower) app.mauri.sukrara.core.model.LinkState("Ana", null) else null)

    fun treatment(
        id: String = "t1",
        kind: TreatmentKind = TreatmentKind.RapidInsulin,
        amount: Double = 4.0,
        minutesAgo: Long = 40,
    ) = Treatment(id, kind, amount, NOW.minus(Duration.ofMinutes(minutesAgo)), modifiedAt = NOW.minus(Duration.ofMinutes(minutesAgo)), origin = TreatmentOrigin.Phone)
}
