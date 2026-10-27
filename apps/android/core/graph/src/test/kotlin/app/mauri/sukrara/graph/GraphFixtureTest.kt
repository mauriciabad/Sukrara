// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.Fixtures.double
import app.mauri.sukrara.graph.Fixtures.long
import app.mauri.sukrara.graph.Fixtures.str
import app.mauri.sukrara.graph.stats.Goal
import app.mauri.sukrara.graph.stats.ShareText
import app.mauri.sukrara.graph.stats.TimeInRange
import app.mauri.sukrara.graph.stats.TirRange
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The renderer's decisions against contracts/graph (share, mark, sensor, place); the Mac runs the same files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GraphFixtureTest {
    private fun cases(path: String) = Fixtures.read(path)["cases"]!!.jsonArray.map { it.jsonObject }

    private fun name(c: JsonObject) = c["name"].str()!!

    @Test
    fun share() {
        for (c in cases("share/share.json")) {
            val goal =
                when (c["goal"].str()) {
                    "veryHigh" -> Goal.VERY_HIGH
                    "above" -> Goal.ABOVE
                    "inRange" -> Goal.IN_RANGE
                    "below" -> Goal.BELOW
                    "veryLow" -> Goal.VERY_LOW
                    else -> null
                }
            val locale = Locale.forLanguageTag(c["locale"].str()!!)
            val x = c["x"].double()!!
            val got = if (c["goal"].str() == "coverage") ShareText.coverage(x, locale) else ShareText.of(x, goal, locale)
            assertEquals(name(c), c["expect"].str(), got)
        }
    }

    @Test
    fun mark() {
        for (c in cases("mark/mark.json")) {
            val t = c["tir"]!!.jsonObject
            fun v(k: String) = t[k].double() ?: 0.0
            val tir = TimeInRange(v("veryLow"), v("low"), v("tight"), v("target"), v("high"), v("veryHigh"), v("coverage"))
            val range = if (c["range"].str() == "tight") TirRange.TIGHT else TirRange.IN_RANGE
            val e = c["expect"]!!.jsonObject
            val spec = RingSpec(86_400, range)
            assertEquals(name(c), e["mark"].str(), TirMark.of(tir, range).name.lowercase())
            assertEquals(name(c), e["text"].str(), TirRing.text(tir, spec, Locale.US))
            val (lo, inr, hi) = GlucoseGraph.pct3(tir.below, tir.inRange, tir.above)
            assertEquals(name(c), e["column"]!!.jsonArray.map { it.long()!!.toInt() }, listOf(lo, inr, hi))
            assertEquals(name(c), e["crown"].toString() == "true", TirMark.column(tir))
            assertEquals(name(c), e["flower"].str(), Bloom.of(tir, range)?.name?.lowercase() ?: "none")
        }
    }

    @Test
    fun sensor() {
        val w = Scenes.words()
        for (c in cases("sensor/life.json")) {
            val now = Scenes.NOW
            val start = now + c["start"].long()!!
            val info = SensorInfo(start, now + c["end"].long()!!, c["warmup"].long()?.let { now + it } ?: (start + 3600))
            val latest = c["latest"].long()?.let { now + it }
            val look = SensorBadge.look(info, now, latest, w)!!
            val e = c["expect"]!!.jsonObject
            assertEquals(name(c), e["label"].str(), look.label)
            assertEquals(name(c), e["ended"].toString() == "true", look.ended)
            assertEquals(name(c), e["life"].double()!!, look.life.toDouble(), 0.001)
            assertEquals(name(c), e["warmup"].str(), look.warmup)
            val ring = SensorBadge.ring(look, info, now, c["countdownHours"].long()!!.toInt())
            val want = e["ring"].double()
            if (want == null) assertNull(name(c), ring) else assertEquals(name(c), want, ring!!, 0.001)
            val pill = e["pill"]
            if (pill == null || pill is JsonNull) {
                assertEquals(name(c), false, look.ended && latest == null)
            } else {
                val p = StatusBadge.replaceSensor(w)
                assertEquals(name(c), pill.jsonObject["text"].str(), p.text)
                assertEquals(name(c), pill.jsonObject["short"].str(), p.short)
            }
        }
    }

    @Test
    fun place() {
        val file = Fixtures.read("place/place.json")
        val d = 1f
        for (r in file["rooms"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(r["label"].str(), r["room"].double()!!, GlucoseGraph.sensorWidth(r["label"].str()!!, d).toDouble(), 0.05)
        }
        val w = Scenes.words()
        for (c in cases("place/place.json")) {
            val card = c["card"]!!.jsonObject
            val graph = card["graph"].toString() == "true"
            val style =
                CardStyle(
                    graph = if (graph) GraphStyle(axisLabels = card["axisLabels"]?.toString() != "false") else null,
                    value = card["value"].toString() == "true",
                    sensorTime = card["sensorTime"]?.toString() != "false",
                )
            val now = Scenes.NOW
            val info =
                when (c["sensor"].str()) {
                    "live" -> SensorInfo(now - 2 * 86_400, now + 13 * 86_400)
                    "warmup" -> SensorInfo(now - 600, now + 15 * 86_400)
                    "ended" -> SensorInfo(now - 15 * 86_400, now - 60)
                    else -> null
                }
            val age = c["age"].long()!!
            val look = SensorBadge.look(info, now, if (c["sensor"].str() == "warmup") null else now - age, w)
            val spot = GlucoseCard.sensorSpot(style, look, Freshness.of(age))
            assertEquals(name(c), c["expect"]!!.jsonObject["spot"].str(), spot.name.lowercase())
            val box = c["expect"]!!.jsonObject["box"]?.jsonArray ?: continue
            val chart = c["chart"]!!.jsonObject
            val top = GlucoseGraph.sensorTop(chart["h"].double()!!.toFloat(), 0f, d)
            assertEquals(name(c), box[1].double()!!, top.toDouble(), 0.05)
            assertEquals(name(c), box[2].double()!!, GlucoseGraph.SENSOR_DP.toDouble(), 0.05)
        }
    }
}
