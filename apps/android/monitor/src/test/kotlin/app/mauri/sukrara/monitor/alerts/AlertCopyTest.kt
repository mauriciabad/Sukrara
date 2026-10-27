// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.MuteLine
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.alerts.TreatmentKind
import app.mauri.sukrara.core.model.DurationWords
import app.mauri.sukrara.core.testing.Contracts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every mute / remind-less line of contracts/alerts/copy.json, worded from this module's en and es strings. */
class AlertCopyTest {
    private fun strings(file: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(file)).getElementsByTagName("string")
        return (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    /** This module's strings, and THE duration formats from `:core:graph`, in one language folder. */
    private fun words(dir: String): AlertWords {
        val own = strings("src/main/res/$dir/strings.xml")
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
            assertEquals("en $c", c.str("en"), AlertCopy.line(lineOf(c), en, clock))
            assertEquals("es $c", c.str("es"), AlertCopy.line(lineOf(c), es, clock))
        }
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
