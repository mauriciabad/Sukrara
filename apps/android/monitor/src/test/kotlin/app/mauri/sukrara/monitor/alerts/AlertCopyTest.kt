// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertReading
import app.mauri.sukrara.alerts.Firing
import app.mauri.sukrara.alerts.MuteLine
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.alerts.TreatmentKind
import app.mauri.sukrara.core.model.DurationWords
import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.designsystem.alerts.AlertWords
import app.mauri.sukrara.designsystem.alerts.MuteText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every mute / remind-less line of contracts/alerts/copy.json, worded by [MuteText] from the en and es strings
 * (`:core:designsystem`'s mute lines, this module's alert texts).
 */
class AlertCopyTest {
    private fun strings(file: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(file)).getElementsByTagName("string")
        return (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    /** The mute lines' and this module's strings, and THE duration formats from `:core:graph`, in one language folder. */
    private fun words(dir: String): AlertWords {
        val own = strings("src/main/res/$dir/strings.xml") + strings("../core/designsystem/src/main/res/$dir/strings.xml")
        val graph = strings("../core/graph/src/main/res/$dir/strings.xml")
        return object : AlertWords {
            override fun get(name: String) = own[name] ?: error("missing string $name in $dir")

            override val durations = DurationWords(
                graph.getValue("duration_minutes"), graph.getValue("duration_hours"), graph.getValue("duration_hours_minutes"),
                graph.getValue("duration_days"), graph.getValue("duration_text_minutes"), graph.getValue("duration_text_hours"),
                graph.getValue("duration_text_hours_minutes"),
            )
        }
    }

    private val en = words("values")
    private val es = words("values-es")

    private fun side(o: JsonObject) = Side.entries.first { it.wire == o.str("side") }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun JsonObject.long(k: String) = getValue(k).jsonPrimitive.int.toLong()

    private fun lineOf(c: JsonObject): MuteLine = when (c.str("state")) {
        "side_muted" -> MuteLine.SideMuted(side(c), c.long("leftMin"))
        "side_after" -> MuteLine.SideAfter(side(c), if (c.str("treatment") == "carbs") TreatmentKind.CARBS else TreatmentKind.RAPID, c.long("leftMin"))
        "side_falling" -> MuteLine.SideFalling(side(c))
        "side_less" -> MuteLine.SideLess(side(c), c.long("intervalMin").toInt())
        "all_timed" -> MuteLine.AllTimed(c.long("leftMin"))
        "all_forever" -> MuteLine.AllForever
        "notif_title" -> MuteLine.SideMuted(side(c), null)
        "notif_text" -> MuteLine.NotifUntil(0)
        "pill" -> MuteLine.Pill(c.long("leftMin"))
        "pill_forever" -> MuteLine.PillForever
        "choice" -> MuteLine.Choice(c.long("min").toInt())
        "choice_forever" -> MuteLine.ChoiceForever
        else -> error("unknown state in copy.json: $c")
    }

    @Test fun everyCaseMatchesInEnglishAndSpanish() {
        val cases = Json.parseToJsonElement(Contracts.file("alerts/copy.json").readText()).jsonObject.getValue("cases").jsonArray
        for (c in cases.map { it.jsonObject }) {
            val clock = { _: Long -> c["until"]?.jsonPrimitive?.content ?: "" }
            assertEquals("en $c", c.str("en"), MuteText.line(lineOf(c), en, clock))
            assertEquals("es $c", c.str("es"), MuteText.line(lineOf(c), es, clock))
        }
    }

    private fun firing(kind: AlertKind, reading: AlertReading?) =
        Firing(kind = kind, id = "e1", n = 1, reading = reading, threshold = 70.0, at = 1_000_000)

    @Test fun anAlertWithoutAReadingNeverShowsAMadeUpValue() {
        for (kind in listOf(AlertKind.LOW, AlertKind.VERY_LOW, AlertKind.HIGH, AlertKind.FALLING_FAST, AlertKind.RISING_FAST)) {
            assertEquals("en $kind", "No recent reading", AlertCopy.body(firing(kind, null), en))
            assertEquals("es $kind", "Sin lectura reciente", AlertCopy.body(firing(kind, null), es))
        }
    }

    @Test fun aLevelAlertShowsTheValueAndArrowAndTheFastOnesTheRate() {
        val r = AlertReading(t = 1_000_000, mgdl = 69, rate = -2.4)
        assertTrue(AlertCopy.body(firing(AlertKind.LOW, r), en).startsWith("69 mg/dL "))
        assertTrue(AlertCopy.body(firing(AlertKind.FALLING_FAST, r), en).endsWith(" · falling 2.4 mg/dL/min"))
        assertEquals("no rate: no made-up 0.0", AlertCopy.body(firing(AlertKind.LOW, r.copy(rate = null)), en),
            AlertCopy.body(firing(AlertKind.FALLING_FAST, r.copy(rate = null)), en))
    }

    @Test fun spanishAndEnglishHaveTheSameKeys() {
        fun keys(dir: String): Set<String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }.toSet()
        }
        assertEquals(keys("values"), keys("values-es"))
    }

}
