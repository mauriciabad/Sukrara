// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.content.res.Configuration
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.graph.stats.GlucosePoint
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.Locale
import kotlin.math.sin

/** Synthetic cards for the renderer tests (never real health data). */
internal object Scenes {
    const val NOW = 1_761_552_000L // 2025-10-27 08:00 UTC

    /** The words for [locale] from the graph's resources (Robolectric). */
    fun words(locale: Locale = Locale.ENGLISH): CardWords {
        val base = RuntimeEnvironment.getApplication()
        val conf = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return CardWords.of(base.createConfigurationContext(conf).resources)
    }

    /** A day of 5-minute readings around [centre] mg/dL, the newest [ageS] old with [rate]. */
    fun day(
        centre: Int,
        ageS: Long = 60,
        rate: Double? = -0.4,
        swing: Int = 35,
    ): List<GlucosePoint> =
        (0 until 288).map { i ->
            val t = NOW - ageS - (287 - i) * 300L
            GlucosePoint(t, (centre + swing * sin(i / 18.0)).toInt(), if (i == 287) rate else null)
        }

    fun treatment(
        id: String,
        kind: TreatmentKind,
        amount: Double,
        agoS: Long,
    ) = Treatment(
        id,
        kind,
        amount,
        Instant.ofEpochSecond(NOW - agoS),
        modifiedAt = Instant.ofEpochSecond(NOW - agoS),
        origin = TreatmentOrigin.Phone,
    )

    fun model(
        readings: List<GlucosePoint>,
        words: CardWords,
        status: CardStatus = CardStatus.None,
        sensor: SensorInfo? = SensorInfo(NOW - 2 * 86_400, NOW + 13 * 86_400),
        treatments: List<Treatment> = emptyList(),
    ) = CardModel(
        readings = readings,
        treatments = treatments,
        nowS = NOW,
        words = words,
        status = status,
        muteRule = MuteRule { t -> if (t.kind == TreatmentKind.RapidInsulin) t.at.epochSecond + 3 * 3600 else null },
        sensor = sensor,
    )
}
