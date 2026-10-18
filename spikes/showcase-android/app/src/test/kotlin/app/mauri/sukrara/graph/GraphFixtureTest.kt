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

    /**
     * The treatment label layout (spikes/graph-fixtures/labels/\*.json, format in the README): the plot's edges, the
     * lanes, and which labels are drawn (entries, text, lane, centre in dp from the chart's left) or dropped. Same files
     * in the Mac's `-graphselftest`.
     */
    @Test fun labels() {
        val files = File(dir(), "labels").listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertTrue("no label fixtures", files.isNotEmpty())
        var n = 0
        val fails = ArrayList<String>()
        for (f in files) {
            val cases = JSONObject(f.readText()).getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val name = "${f.name}: ${c.getString("name")}"
                val got = labelDump(c)
                val want = c.getJSONObject("expect")
                if (!sameDump(want, got)) fails += "$name\n  want ${want}\n  got  $got"
                n++
            }
        }
        assertTrue("label fixtures failed:\n" + fails.joinToString("\n"), fails.isEmpty())
        println("label fixtures: $n cases passed")
    }

    /** What the graph decides for a label case, in the fixtures' `expect` form. */
    private fun labelDump(c: JSONObject): JSONObject {
        val w = c.getJSONObject("chart").getDouble("w").toFloat()
        val h = c.getJSONObject("chart").getDouble("h").toFloat()
        val windowS = (c.getDouble("hours") * 3600).toLong()
        val (left, right) = GlucoseGraph.plotX(0f, w, c.optBoolean("pct", true), true, 1f)
        val trs = c.getJSONArray("treatments").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Treatment(id = it.getString("id"), k = it.getString("k"), a = it.getDouble("a"), t = now + it.getLong("t"), u = 0, o = "phone")
            }
        }
        val from = now - windowS
        val lay = TreatmentMarks.layout(trs.filter { it.t in from..now }, { t -> left + (t - from).toFloat() / windowS * (right - left) },
            now, 0f, w, GlucoseGraph.lanes(h, 1f), 1f)
        fun r(v: Float) = Math.round(v * 2) / 2.0
        return JSONObject()
            .put("plot", org.json.JSONArray(listOf(r(left), r(right))))
            .put("lanes", GlucoseGraph.lanes(h, 1f))
            .put("labels", org.json.JSONArray(lay.labels.sortedBy { it.left + it.w / 2 }.map { l ->
                JSONObject().put("ids", org.json.JSONArray(l.members.map { it.id })).put("text", l.text)
                    .put("lane", l.lane).put("x", r(l.left + l.w / 2))
            }))
            .put("dropped", org.json.JSONArray(lay.dropped.map { g -> org.json.JSONArray(g.map { it.id }) }))
    }

    /** Equal, with dp values within 0.5 (float vs double, rounding to the nearest 0.5). */
    private fun sameDump(a: JSONObject, b: JSONObject): Boolean {
        fun near(x: Double, y: Double) = kotlin.math.abs(x - y) <= 0.5
        val pa = a.getJSONArray("plot"); val pb = b.getJSONArray("plot")
        if (!near(pa.getDouble(0), pb.getDouble(0)) || !near(pa.getDouble(1), pb.getDouble(1))) return false
        if (a.getInt("lanes") != b.getInt("lanes")) return false
        if (a.getJSONArray("dropped").toString() != b.getJSONArray("dropped").toString()) return false
        val la = a.getJSONArray("labels"); val lb = b.getJSONArray("labels")
        if (la.length() != lb.length()) return false
        return (0 until la.length()).all { i ->
            val x = la.getJSONObject(i); val y = lb.getJSONObject(i)
            x.getJSONArray("ids").toString() == y.getJSONArray("ids").toString() && x.getString("text") == y.getString("text") &&
                x.getInt("lane") == y.getInt("lane") && near(x.getDouble("x"), y.getDouble("x"))
        }
    }
}
