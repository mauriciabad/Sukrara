// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.Fixtures.bool
import app.mauri.sukrara.graph.Fixtures.double
import app.mauri.sukrara.graph.Fixtures.str
import app.mauri.sukrara.graph.stats.GlucosePoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/graph/trend: the one trend arrow (Mac: GraphTests TrendFixtureTests). The drawn arrow's angle and double,
 * the text arrow, and the face's [GlucoseLook.arrowText] that every text place writes, all from [TrendAngle].
 */
class TrendFixtureTest {
    private val cases = (Fixtures.read("trend/trend.json")["cases"] as JsonArray).map { it.jsonObject }

    @Test fun `the fixture has cases`() = assertTrue(cases.isNotEmpty())

    @Test fun `text, angle and double follow the fixture`() {
        val fails =
            cases.mapNotNull { c ->
                val rate = c["rate"].double()
                val x = c["expect"] as JsonObject
                val got = Triple(TrendAngle.text(rate), rate?.let { TrendAngle.deg(it).toDouble() }, rate?.let(TrendAngle::isDouble))
                val want = Triple(x["text"].str(), x["deg"].double(), x["double"].bool())
                val same =
                    got.first == want.first && got.third == want.third &&
                        (
                            got.second == want.second ||
                                (got.second != null && want.second != null && kotlin.math.abs(got.second!! - want.second!!) < 1e-3)
                        )
                if (same) null else "${c["name"].str()}: got $got, want $want"
            }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    /** The face of a fresh reading at each rate writes the fixture's arrow; a struck (stale) one the ring, like the card. */
    @Test fun `the face's arrow text is the fixture's`() {
        val now = 1_800_000_000L
        cases.forEach { c ->
            val rate = c["rate"].double()
            val want = (c["expect"] as JsonObject)["text"].str()
            val fresh = GlucoseCard.face(GlucosePoint(now - 30, 100, rate), now, null, CardSurface.NOTIFICATION_TEXT)
            assertEquals(c["name"].str(), want, fresh.arrowText)
            val stale = GlucoseCard.face(GlucosePoint(now - 6 * 60, 100, rate), now, null, CardSurface.NOTIFICATION_TEXT)
            assertEquals(c["name"].str(), TrendMark.RING, stale.arrowText)
        }
        assertEquals(null, GlucoseCard.face(null, now, null, CardSurface.NOTIFICATION_TEXT).arrowText)
    }
}
