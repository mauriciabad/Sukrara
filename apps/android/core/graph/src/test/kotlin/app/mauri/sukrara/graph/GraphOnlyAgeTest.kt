// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import app.mauri.sukrara.graph.Fixtures.double
import app.mauri.sukrara.graph.Fixtures.long
import app.mauri.sukrara.graph.Fixtures.str
import app.mauri.sukrara.graph.stats.GlucosePoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * A card without a value (the graph widget) shows its data's age: every case of contracts/graph/freshness, drawn
 * through the widget-graph surface at 332 × 138, marks its `graph_only` (Mac: FreshnessFixtureTests.graphOnly).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GraphOnlyAgeTest {
    private val savedZone = TimeZone.getDefault()
    private val savedLocale = Locale.getDefault()
    private val now = 1_800_000_000L

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        RoundedFont.init(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    @Test fun everyCaseMarksItsAge() {
        val cases = Fixtures.read("freshness/freshness.json")["cases"] as JsonArray
        assertTrue(cases.isNotEmpty())
        val words = Scenes.words()
        val st = CardStyle(graph = GraphStyle(), rings = emptyList(), value = false, sensorTime = false)
        val fails =
            cases.mapNotNull { e ->
                val c = e.jsonObject
                val want = (c["expect"] as JsonObject)["graph_only"].str()
                val age = c["age"].long()
                val sgv = c["sgv"].long()?.toInt()
                val readings =
                    if (age == null || sgv == null) {
                        emptyList()
                    } else {
                        (60 downTo 1).map { GlucosePoint(now - age - it * 60L, 120, 0.0) } + GlucosePoint(now - age, sgv, c["rate"].double())
                    }
                val model = CardModel(readings, emptyList(), now, words)
                val lay = GlucoseCard.draw(Canvas(), 332f, 138f, model, st, GraphPalette.of(false), 1f, CardSurface.WIDGET_GRAPH, CardPart.ALL)
                val mark = lay.first("graph.age")?.info
                val chart = lay.first("chart") != null
                val ok =
                    when (want) {
                        "none" -> mark == null && chart
                        "face" -> mark == null && lay.first("value.none") != null
                        else -> mark == want && chart
                    }
                if (ok) null else "${c["name"].str()}: want $want, got mark=$mark chart=$chart"
            }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    private fun graphOnly(
        age: Long,
        w: Float,
        h: Float = 138f,
    ): CardLayout {
        val readings = (60 downTo 1).map { GlucosePoint(now - age - it * 60L, 120, 0.0) } + GlucosePoint(now - age, 118, 0.2)
        val st = CardStyle(graph = GraphStyle(), rings = emptyList(), value = false, sensorTime = false)
        val model = CardModel(readings, emptyList(), now, Scenes.words())
        return GlucoseCard.draw(Canvas(), w, h, model, st, GraphPalette.of(false), 1f, CardSurface.WIDGET_GRAPH, CardPart.ALL)
    }

    /** G2 review: a widget drawn while fresh that the launcher stops redrawing still says when its data is from. */
    @Test fun freshChartShowsTheReadingTime() {
        val lay = graphOnly(age = 10, w = 332f)
        assertEquals("time", lay.first("graph.age")?.info)
        val time = Freshness.timeText(now - 10, now)
        assertTrue("$time in ${lay.all("time.text").map { it.info }}", lay.all("time.text").any { it.info == time })
    }

    /** The pill never grows past the chart, however narrow the widget (the widest label: icon + time). */
    @Test fun pillStaysInsideTheChart() {
        for (w in listOf(40f, 60f, 90f, 140f, 332f)) {
            for (age in listOf(10L, 400L, 1500L)) {
                val lay = graphOnly(age, w, h = 60f)
                val chart = checkNotNull(lay.first("chart")?.box) { "w=$w age=$age: no chart" }
                val pill = checkNotNull(lay.first("graph.age")?.box) { "w=$w age=$age: no pill" }
                assertTrue("w=$w age=$age: $pill outside $chart", pill.left >= chart.left && pill.right <= chart.right)
            }
        }
    }
}
