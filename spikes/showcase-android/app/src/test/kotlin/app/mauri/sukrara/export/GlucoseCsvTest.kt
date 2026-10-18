// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.export

import app.mauri.sukrara.treatments.Treatment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * The CSV export ([GlucoseCsv]) against spikes/export-fixtures (format in its README): escaping, time zones and the
 * daylight-saving change, units, empty ranges. The Mac runs the same files in `Sukrara -exportselftest`.
 */
class GlucoseCsvTest {
    private fun dir(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "spikes/export-fixtures") }.first { it.isDirectory }

    @Test fun fixtures() {
        val files = dir().listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertTrue("no fixtures", files.isNotEmpty())
        var n = 0
        for (f in files) {
            val cases = JSONObject(f.readText()).getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val rs = c.getJSONArray("readings")
                val readings = (0 until rs.length()).map { rs.getJSONObject(it) }.map {
                    GlucoseCsv.Row(it.getLong("t"), it.getInt("sgv"), if (it.has("rate")) it.getDouble("rate") else null)
                }
                val ts = c.getJSONArray("treatments")
                val treatments = (0 until ts.length()).map { ts.getJSONObject(it) }.map {
                    Treatment(id = it.getString("id"), k = it.getString("k"), a = it.optDouble("a", 0.0), t = it.getLong("t"),
                        n = it.optString("n"), u = 1, del = it.optBoolean("del"), o = it.getString("o"))
                }
                val sensor = c.optJSONObject("sensor")?.let { GlucoseCsv.Sensor(it.getString("serial"), it.getLong("start"), it.getLong("end")) }
                val got = GlucoseCsv.write(readings, treatments, c.getLong("from"), c.getLong("to"), ZoneId.of(c.getString("zone")),
                    c.getLong("generated"), sensor, c.optBoolean("mmol"))
                val e = c.getJSONArray("expect")
                val want = (0 until e.length()).joinToString("") { e.getString(it) + "\r\n" }
                assertEquals("${f.name}: ${c.getString("name")}", want, got)
                n++
            }
        }
        assertTrue(n >= 6)
    }

    @Test fun cells() {
        assertEquals("plain", GlucoseCsv.escape("plain"))
        assertEquals("\"a,b\"", GlucoseCsv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", GlucoseCsv.escape("say \"hi\""))
        assertEquals("'-2 u", GlucoseCsv.textCell("-2 u"))
        assertEquals("'@x", GlucoseCsv.textCell("@x"))
        assertEquals("", GlucoseCsv.textCell(""))
        assertEquals("0.0", GlucoseCsv.tenths(-0.04))
        assertEquals("-1.3", GlucoseCsv.tenths(-1.25))
        assertEquals("12.0", GlucoseCsv.tenths(12.0))
    }

    @Test fun fileNameHasOnlyThePeriod() {
        val z = ZoneId.of("Europe/Madrid")
        // 2026-10-05 00:00 → 2026-10-19 24:00 local
        assertEquals("sukrara-glucose-2026-10-05-to-2026-10-19.csv", GlucoseCsv.fileName(1_791_151_200, 1_792_447_200, z))
    }
}
