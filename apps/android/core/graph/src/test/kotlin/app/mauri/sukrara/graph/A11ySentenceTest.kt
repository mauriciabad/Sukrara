// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The card's screen-reader sentence: value, unit, trend and range in words, age; then the chart summary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class A11ySentenceTest {
    private fun one(
        sgv: Int,
        ageS: Long,
        rate: Double?,
    ) = listOf(GlucosePoint(Scenes.NOW - ageS, sgv, rate))

    @Test
    fun valueInEnglish() {
        val m = Scenes.model(one(112, 120, -1.5), Scenes.words(), sensor = null)
        assertEquals("112 milligrams per decilitre, falling, in range, 2 min ago", A11y.value(m, Bands(), CardSurface.TEST))
    }

    @Test
    fun valueInSpanish() {
        val m = Scenes.model(one(250, 30, 2.5), Scenes.words(Locale.forLanguageTag("es")), sensor = null)
        assertEquals("250 miligramos por decilitro, subiendo rápido, alta, ahora mismo", A11y.value(m, Bands(), CardSurface.TEST))
    }

    @Test
    fun staleValueSaysOldAndNoTrend() {
        val m = Scenes.model(one(60, 6 * 60, -1.0), Scenes.words(), sensor = null)
        assertEquals(
            "60 milligrams per decilitre, old reading, trend unknown, low, 6 min ago",
            A11y.value(m, Bands(), CardSurface.TEST),
        )
    }

    @Test
    fun noReadingsAndStatusPill() {
        val w = Scenes.words()
        assertEquals("Glucose: no readings yet", A11y.value(Scenes.model(emptyList(), w, sensor = null), Bands(), CardSurface.TEST))
        val needed = Scenes.model(one(60, 60, null), w, status = CardStatus.Needed(lowSide = true), sensor = null)
        assertEquals("60 milligrams per decilitre, trend unknown, low, 1 min ago. Eat carbs", A11y.value(needed, Bands(), CardSurface.TEST))
    }

    @Test
    fun pillIsSpokenOnlyWhenDrawn() {
        val w = Scenes.words()
        val needed = Scenes.model(one(60, 60, null), w, status = CardStatus.Needed(lowSide = true), sensor = null)
        assertEquals(
            "60 milligrams per decilitre, trend unknown, low, 1 min ago",
            A11y.value(needed, Bands(), CardSurface.TEST, badge = false),
        )
        val noValue = Scenes.model(emptyList(), w, status = CardStatus.Muted(CardStatus.NEVER), sensor = null)
        assertEquals("Glucose: no readings yet", A11y.value(noValue, Bands(), CardSurface.TEST))
    }

    @Test
    fun endedMuteHasNoPill() {
        val w = Scenes.words()
        val now = Scenes.NOW
        assertNull(StatusBadge.look(CardStatus.Taken(TreatmentKind.RapidInsulin, now - 3600, now), now, w))
        assertNull(StatusBadge.look(CardStatus.Paused(lowSide = true, until = now - 1), now, w))
        assertNull(StatusBadge.look(CardStatus.Muted(now), now, w))
        assertEquals("1m left", StatusBadge.look(CardStatus.Paused(lowSide = true, until = now + 1), now, w)?.text)
    }

    @Test
    fun placeHoursZeroMeansTheDefault() {
        val c = GraphConfig(defaultHours = 12, placeHours = mapOf(GraphPlace.entries.first() to 0))
        assertEquals(12, c.hours(GraphPlace.entries.first()))
    }

    @Test
    fun sensorEndedAndChartSummary() {
        val w = Scenes.words()
        val ended = Scenes.model(Scenes.day(140), w, sensor = SensorInfo(Scenes.NOW - 15 * 86_400, Scenes.NOW - 60))
        assertEquals("Sensor ended. Replace sensor", A11y.value(ended, Bands(), CardSurface.TEST))
        val chart = A11y.chart(Scenes.model(Scenes.day(140), w), GraphStyle())
        assertTrue(chart, Regex("Last 12 hours: \\d+% in range, \\d+% below, \\d+% above").matches(chart))
        assertEquals("Last 12 hours: no data", A11y.chart(Scenes.model(emptyList(), w), GraphStyle()))
    }
}
