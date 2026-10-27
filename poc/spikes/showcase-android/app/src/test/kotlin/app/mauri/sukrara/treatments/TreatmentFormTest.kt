// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Calendar

/** The Add treatment dialog's form (spikes/libreview/treatment-form.json, the cases the Mac's -treatmentsselftest runs). */
class TreatmentFormTest {
    private val fx: JSONObject = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "spikes/libreview/treatment-form.json") }.first { it.isFile }.let { JSONObject(it.readText()) }

    @Test fun limits() {
        val l = fx.getJSONObject("limits")
        assertEquals(l.getDouble("units"), TreatmentForm.MAX_UNITS, 0.0)
        assertEquals(l.getDouble("grams"), TreatmentForm.MAX_GRAMS, 0.0)
        assertEquals(l.getInt("noteBytes"), TreatmentForm.NOTE_BYTES)
    }

    @Test fun formsToEntries() {
        val forms = fx.getJSONArray("forms")
        for (i in 0 until forms.length()) {
            val f = forms.getJSONObject(i)
            val name = f.getString("name")
            val got = TreatmentForm.entries(f.getString("rapid"), f.getString("long"), f.getString("carbs"), f.getString("note"), 1000)
            if (f.isNull("entries")) { assertNull(name, got); continue }
            val want = f.getJSONArray("entries")
            assertEquals(name, (0 until want.length()).map { j -> want.getJSONArray(j).let { listOf(it.getString(0), it.getDouble(1), it.getString(2)) } },
                got!!.map { listOf(it.k, it.a, it.note) })
            got.forEach { assertEquals(name, 1000L, it.at) }
        }
    }

    @Test fun amountFilter() {
        val fs = fx.getJSONArray("filters")
        for (i in 0 until fs.length()) fs.getJSONObject(i).let { assertEquals(it.getString("in"), it.getString("out"), TreatmentForm.filterAmount(it.getString("in"))) }
    }

    @Test fun minutesAgo() {
        val ms = fx.getJSONArray("minutes")
        val now = 1_750_000_000L
        for (i in 0 until ms.length()) {
            val c = ms.getJSONObject(i)
            val typed = c.getString("typed")
            val m = TreatmentForm.agoMinutes(typed)
            if (c.optBoolean("invalid")) { assertNull(typed, m); continue }
            val got = TreatmentForm.agoTime(now, m!!)
            if (c.isNull("ago_s")) assertNull(typed, got) else assertEquals(typed, c.getLong("ago_s"), now - got!!)
        }
        val fs = fx.getJSONArray("minute_filters")
        for (i in 0 until fs.length()) fs.getJSONObject(i).let { assertEquals(it.getString("in"), it.getString("out"), TreatmentForm.filterMinutes(it.getString("in"))) }
    }

    @Test fun timePicker() {
        val ts: JSONArray = fx.getJSONArray("times")
        for (i in 0 until ts.length()) {
            val c = ts.getJSONObject(i)
            val (h, m, s) = c.getString("now").split(":").map { it.toInt() }
            val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.JUNE, 15, h, m, s) }
            val (ph, pm) = c.getString("pick").split(":").map { it.toInt() }
            val got = TreatmentForm.pickTime(now, ph, pm)
            val what = "${c.getString("now")} pick ${c.getString("pick")}"
            if (c.isNull("ago_s")) assertNull(what, got)
            else assertEquals(what, c.getLong("ago_s"), now.timeInMillis / 1000 - got!!)
        }
    }
}
