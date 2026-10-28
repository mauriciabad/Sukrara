// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.Fixtures.bool
import app.mauri.sukrara.graph.Fixtures.double
import app.mauri.sukrara.graph.Fixtures.long
import app.mauri.sukrara.graph.Fixtures.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/graph/freshness: the one look every surface takes (Mac: GraphTests). The per-surface pass (every glyph
 * surface's adapter) is added with the renderer's `GlucoseCard.face`.
 */
class FreshnessFixtureTest {
    private val file = Fixtures.read("freshness/freshness.json")

    @Test fun steps() {
        val steps = file["steps"]!!.jsonObject
        val from = steps["from"]!!.jsonArray.map { it.long()!! }
        val next = steps["next"]!!.jsonArray.map { it.long() }
        from.zip(next).forEach { (age, expected) ->
            assertEquals("next change after a reading $age s old", expected, Freshness.nextChangeAt(0, age))
        }
    }

    @Test fun cases() {
        val now = 1_800_000_000L
        val cases = file["cases"] as JsonArray
        assertTrue(cases.isNotEmpty())
        val fails =
            cases.mapNotNull { e ->
                val c = e.jsonObject
                val age = c["age"].long()
                val look =
                    Freshness.look(
                        sgv = c["sgv"].long()?.toInt(),
                        t = age?.let { now - it },
                        rate = c["rate"].double(),
                        nowS = now,
                        ended = c["ended"].bool() ?: false,
                    )
                val x = c["expect"] as JsonObject
                val got =
                    mapOf(
                        "state" to look.state.name.lowercase(),
                        "text" to look.text,
                        "strike" to look.strike.toString(),
                        "trend" to look.trend?.let { if (it == TrendMark.ARROW) "arrow" else "none" },
                        "time" to look.fresh.showTime.toString(),
                        "graph" to look.fresh.showGraph.toString(),
                        "graph_only" to Freshness.of(age).ageMark.key,
                    )
                val want =
                    mapOf(
                        "state" to x["state"].str(),
                        "text" to x["text"].str(),
                        "strike" to x["strike"].bool().toString(),
                        "trend" to x["trend"].str(),
                        "time" to x["time"].bool().toString(),
                        "graph" to x["graph"].bool().toString(),
                        "graph_only" to x["graph_only"].str(),
                    )
                if (got == want) null else "${c["name"].str()}: got $got, want $want"
            }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    /** A new sensor's warm-up comes before any value, even with a reading (not in the shared fixture yet). */
    @Test fun warmupWinsOverValue() {
        val look = Freshness.look(sgv = 120, t = 0, rate = 1.0, nowS = 30, warmup = "42m")
        assertEquals(GlucoseLook.State.WARMING_UP, look.state)
        assertEquals(null, look.text)
        assertEquals("42m", look.warmup)
    }
}
