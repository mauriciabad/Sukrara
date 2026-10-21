// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** [Durations] against THE shared cases (spikes/durations/durations.json; the Mac runs the same in -settingsselftest). */
class DurationsTest {
    private val fixture: JSONObject = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "spikes/durations/durations.json") }.first { it.isFile }.readText().let(::JSONObject)

    private fun cases(a: JSONArray) = (0 until a.length()).map { a.getJSONArray(it) }

    private fun words(dir: String): Words {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        val m = (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        return Words(durMinutes = m.getValue("duration_minutes"), durHours = m.getValue("duration_hours"),
            durHoursMinutes = m.getValue("duration_hours_minutes"), durDays = m.getValue("duration_days"),
            durTextMinutes = m.getValue("duration_text_minutes"), durTextHours = m.getValue("duration_text_hours"),
            durTextHoursMinutes = m.getValue("duration_text_hours_minutes"))
    }

    private fun check(f: JSONObject, w: Words, lang: String) {
        for (c in cases(f.getJSONArray("text"))) assertEquals("$lang text ${c.getLong(0)}", c.getString(1), Durations.text(c.getLong(0), w))
        for (c in cases(f.getJSONArray("compact"))) assertEquals("$lang compact ${c.getLong(0)}", c.getString(1), Durations.compact(c.getLong(0), w))
        for (c in cases(f.getJSONArray("whole"))) assertEquals("$lang whole ${c.getLong(0)}", c.getString(1), Durations.whole(c.getLong(0), w))
    }

    @Test fun english() {
        check(fixture, Words(), "default")
        check(fixture, words("values"), "en")
    }

    @Test fun spanish() = check(fixture.getJSONObject("es"), words("values-es"), "es")

    @Test fun parse() {
        for (c in cases(fixture.getJSONArray("parse"))) {
            val want = if (c.isNull(1)) null else c.getInt(1)
            assertEquals("parse '${c.getString(0)}'", want, Durations.parse(c.getString(0)))
        }
    }
}
