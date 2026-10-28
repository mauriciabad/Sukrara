// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.export

import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import app.mauri.sukrara.integrations.CurrentSensor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/** The period of an export: the current sensor, a number of days, or everything on this phone. */
sealed interface ExportPeriod {
    data object Sensor : ExportPeriod

    data class Days(val days: Int) : ExportPeriod

    data object All : ExportPeriod

    companion object {
        val DAYS = listOf(7, 14, 30)
        val DEFAULT: ExportPeriod = Days(14)
    }
}

/** What one export holds, and how many rows the file will have. */
data class ExportData(
    val from: Instant,
    val to: Instant,
    val readings: List<Reading>,
    val treatments: List<TreatmentRecord>,
    val sensor: CurrentSensor?,
) {
    /** Counted once (on the loading dispatcher), not on every recomposition. */
    val readingRows: Int = GlucoseCsv.thin(readings, from, to).size
    val treatmentRows: Int = treatments.count { !it.treatment.deleted && it.treatment.at >= from && it.treatment.at < to }
}

/**
 * Settings › Export data: what goes into [GlucoseCsv], only from what is already on this phone (no fetching). The
 * wearer's own readings and treatments; a follower's are the followed person's it received (the same stores).
 */
class Exporter(
    private val readings: ReadingsRepository,
    private val treatments: TreatmentsRepository,
    /** The current sensor (serial, start, end), null when none is known (a follower never knows the serial). */
    private val sensor: Flow<CurrentSensor?>,
    private val clock: Clock,
) {
    /** The current sensor, for the period choice ("This sensor" only when one is known). */
    suspend fun currentSensor(): CurrentSensor? = sensor.first()

    /** Reads the stores for [period] (Room: suspends on its own IO dispatcher). */
    suspend fun load(period: ExportPeriod): ExportData {
        val now = clock.now()
        val to = now.plusSeconds(1)
        val s = sensor.first()
        val from =
            when (period) {
                ExportPeriod.Sensor -> s?.start ?: now - Duration.ofDays(14)
                is ExportPeriod.Days -> now - Duration.ofDays(period.days.toLong())
                ExportPeriod.All -> Instant.EPOCH
            }
        val rs = readings.range(from, to)
        val ts = treatments.changedSince(Instant.EPOCH).filter { it.treatment.at >= from && it.treatment.at < to }
        val start =
            if (period == ExportPeriod.All) {
                listOfNotNull(rs.minOfOrNull { it.t }, ts.filter { !it.treatment.deleted }.minOfOrNull { it.treatment.at }).minOrNull()
                    ?: now
            } else {
                from
            }
        return ExportData(start, to, rs, ts, s)
    }

    fun csv(d: ExportData): String = GlucoseCsv.write(d.readings, d.treatments, d.from, d.to, clock.zone, clock.now(), d.sensor)

    fun fileName(d: ExportData): String = GlucoseCsv.fileName(d.from, d.to, clock.zone)
}
