// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.treatments.LastTreatment
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.treatments.TreatmentMarks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared card decisions (spikes/graph-fixtures/\*.json, format in its README): status, badge, last-treatment slot
 * and amount labels. The Mac runs the same files in `Sukrara -graphselftest`, so "one graph everywhere" is checked
 * by both platforms against one set of expectations.
 */
class GraphFixtureTest {
    private val now = 1_800_000_000L

    private fun dir(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "spikes/graph-fixtures") }.first { it.isDirectory }

    private fun status(s: AlertStatus): String = when (s) {
        AlertStatus.None -> "none"
        is AlertStatus.Needed -> "needed:${s.kind}"
        is AlertStatus.Taken -> "taken:${s.kind}:${s.treatment.id}"
    }

    @Test fun fixtures() {
        val files = dir().listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertTrue("no fixtures", files.isNotEmpty())
        var n = 0
        for (f in files) {
            val cases = JSONObject(f.readText()).getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val name = "${f.name}: ${c.getString("name")}"
                val settings = c.optJSONObject("settings")?.let(AlertSettings::fromJson) ?: AlertSettings()
                val r = c.getJSONObject("reading").let { Reading(now + it.getLong("t"), it.getInt("sgv"), null) }
                val trs = c.getJSONArray("treatments").let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }.map {
                        Treatment(id = it.getString("id"), k = it.getString("k"), a = it.getDouble("a"), t = now + it.getLong("t"),
                            n = it.optString("n", ""), u = 0, o = "phone")
                    }
                }
                val e = c.getJSONObject("expect")
                val st = AlertStatus.of(r, settings, trs, now)
                assertEquals("$name: status", e.getString("status"), status(st))
                if (st is AlertStatus.Taken) assertEquals("$name: until", now + e.getLong("until"), st.until)
                val look = StatusBadge.look(st, now)
                val badge = e.optJSONObject("badge")
                if (badge == null) assertEquals("$name: badge", null, look)
                else assertEquals("$name: badge",
                    StatusBadge.Look(badge.getString("kind"), badge.getString("text"), badge.getString("short"), badge.getBoolean("tonal")), look)
                val last = LastTreatment.of(trs, settings, st, now)
                val el = e.optJSONObject("last")
                if (el == null) assertEquals("$name: last", null, last)
                else {
                    assertEquals("$name: last id", el.getString("id"), last?.tr?.id)
                    assertEquals("$name: last until", if (el.isNull("until")) null else now + el.getLong("until"), last?.until)
                }
                e.optJSONObject("amounts")?.let { am ->
                    for (id in am.keys()) assertEquals("$name: amount $id", if (am.isNull(id)) null else am.getString(id),
                        TreatmentMarks.amount(trs.first { it.id == id }))
                }
                n++
            }
        }
        println("graph fixtures: $n cases passed")
    }
}
