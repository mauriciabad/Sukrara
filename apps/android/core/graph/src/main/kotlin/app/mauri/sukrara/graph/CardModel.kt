// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.GlucoseMetrics
import app.mauri.sukrara.graph.stats.GlucosePoint
import kotlin.math.abs

/**
 * Everything a card shows, decided by the caller from the one glucose state: the renderer never reads app singletons
 * (alerts, sensor, settings). [readings] oldest or newest first (the renderer sorts nothing it doesn't need);
 * [status] = the alert answer behind the badge (precomputed from `:core:alerts`); [muteRule] = how long each treatment
 * mutes alerts (the chart's mute lines and the last-treatment ring; null = none); [sensor] = the wearer's sensor
 * (null = nothing known); [countdownHours] = the sensor countdown window; [words] = the phone's language.
 */
data class CardModel(
    val readings: List<GlucosePoint>,
    val treatments: List<Treatment>,
    val nowS: Long,
    val words: CardWords,
    val status: CardStatus = CardStatus.None,
    val muteRule: MuteRule? = null,
    val sensor: SensorInfo? = null,
    val countdownHours: Int = SensorLife.DEFAULT_COUNTDOWN_HOURS,
    /** The report numbers of a slot's window; the default computes them from [readings]. */
    val metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)? = null,
) {
    val latest: GlucosePoint? by lazy { readings.maxByOrNull { it.t } }

    fun metrics(
        spec: RingSpec,
        bands: Bands,
    ): GlucoseMetrics = metricsOf?.invoke(spec, bands) ?: GlucoseMetrics.of(readings, nowS - spec.windowS, nowS, bands)
}

/** When a treatment's alert mute ends (epoch s), or null when its kind mutes nothing (the user's alert settings). */
fun interface MuteRule {
    fun muteUntil(t: Treatment): Long?
}

/**
 * What the glucose needs from the user now, for the badge under the value (the alert runtime maps its `AlertStatus`
 * here; the renderer stays free of `:core:alerts`). [lowSide] = the low side (very low / low / falling fast).
 */
sealed interface CardStatus {
    data object None : CardStatus

    /** Out of range, nothing covers it: "Eat carbs" / "Take insulin". */
    data class Needed(
        val lowSide: Boolean,
    ) : CardStatus

    /** A logged [treatment] (its kind and time) covers it until [until]. */
    data class Taken(
        val treatment: TreatmentKind,
        val at: Long,
        val until: Long,
    ) : CardStatus

    /** Muted on this device while out of range; [until] = [NEVER] without an end. */
    data class Paused(
        val lowSide: Boolean,
        val until: Long,
    ) : CardStatus

    /** Mute all, in range or without a current reading; [until] = [NEVER] without an end. */
    data class Muted(
        val until: Long,
    ) : CardStatus

    companion object {
        /** "Until you turn it off" (the alert engine's NEVER). */
        const val NEVER = Long.MAX_VALUE / 4
    }
}

/** The wearer's sensor as the card needs it: start, end and warm-up end (epoch s). */
data class SensorInfo(
    val startS: Long,
    val endS: Long,
    val warmupEndS: Long = startS + 60L * SensorLife.DEFAULT_WARMUP_MIN,
) {
    val lengthS: Long get() = (endS - startS).coerceAtLeast(1)
}

/** Treatment fields in the card's units: epoch seconds and the kind's contract id. */
internal val Treatment.t: Long get() = at.epochSecond

internal val Treatment.k: String get() = kind.id

/** The contract id of a treatment kind (marks, badges, settings `graph.marks`). */
val TreatmentKind.id: String
    get() =
        when (this) {
            TreatmentKind.RapidInsulin -> "rapid"
            TreatmentKind.LongInsulin -> "long"
            TreatmentKind.Carbs -> "carbs"
            TreatmentKind.Note -> "note"
        }

/** The trend arrow's geometry for a rate (mg/dL/min): ±3 is straight up / down, from there a double arrow. */
object TrendAngle {
    const val FULL_SCALE = 3.0

    fun deg(rate: Double): Float = (-(rate / FULL_SCALE).coerceIn(-1.0, 1.0) * 90).toFloat()

    fun isDouble(rate: Double) = abs(rate) >= FULL_SCALE
}
