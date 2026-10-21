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
        is AlertStatus.Paused -> "paused:${s.kind}"
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
                // the alert notification's Mute in force: side → seconds from now
                val paused = c.optJSONObject("paused")?.let { p -> p.keys().asSequence().associateWith { now + p.getLong(it) } } ?: emptyMap()
                val st = AlertStatus.of(r, settings, trs, now, paused)
                assertEquals("$name: status", e.getString("status"), status(st))
                if (st is AlertStatus.Taken) assertEquals("$name: until", now + e.getLong("until"), st.until)
                if (st is AlertStatus.Paused) assertEquals("$name: until", now + e.getLong("until"), st.until)
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

    /** The sensor badge + countdown ring (the files in spikes/graph-fixtures/sensor); the same in the Mac's `-graphselftest`. */
    /** Clock times in the 12/24-hour setting and the graph's hour labels (graph-fixtures/clock, Mac: -graphselftest). */
    @Test fun clock() {
        val files = File(dir(), "clock").listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertTrue("no clock fixtures", files.isNotEmpty())
        fun norm(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ')
        val fails = ArrayList<String>()
        var n = 0
        for (f in files) {
            val cases = JSONObject(f.readText()).getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val loc = java.util.Locale.forLanguageTag(c.getString("locale"))
                val m = c.getInt("minute"); val h24 = c.getBoolean("hour24")
                val time = norm(Clock.minuteOfDay(m, h24, loc)); val axis = norm(Clock.hourLabel(m / 60, h24, loc))
                val want = c.getJSONObject("expect")
                if (time != want.getString("time") || axis != want.getString("axis")) fails += "${f.name}: ${c.getString("name")} (got $time / $axis)"
                n++
            }
        }
        assertTrue("clock fixtures: $n cases, failed:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    /** Time shares against a goal (graph-fixtures/share, Mac: -graphselftest). */
    @Test fun share() {
        val files = File(dir(), "share").listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertTrue("no share fixtures", files.isNotEmpty())
        val fails = ArrayList<String>()
        var n = 0
        for (f in files) {
            val cases = JSONObject(f.readText()).getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val loc = java.util.Locale.forLanguageTag(c.getString("locale"))
                val x = c.getDouble("x")
                val got = when (val g = if (c.isNull("goal")) null else c.getString("goal")) {
                    null -> ShareText.of(x, null, loc)
                    "coverage" -> ShareText.coverage(x, loc)
                    else -> ShareText.of(x, Goal.entries.first { it.name.replace("_", "").equals(g, ignoreCase = true) }, loc)
                }
                if (got != c.getString("expect")) fails += "${f.name}: ${c.getString("name")} (got $got)"
                n++
            }
        }
        assertTrue("share fixtures: $n cases, failed:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    /** The ring's mark and the % column's crown (graph-fixtures/mark, Mac: -graphselftest). */
    @Test fun mark() {
        val files = File(dir(), "mark").listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertTrue("no mark fixtures", files.isNotEmpty())
        val fails = ArrayList<String>()
        var n = 0
        for (f in files) {
            val cases = JSONObject(f.readText()).getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val t = c.getJSONObject("tir")
                val tir = TimeInRange(t.optDouble("veryLow", 0.0), t.optDouble("low", 0.0), t.optDouble("tight", 0.0),
                    t.optDouble("target", 0.0), t.optDouble("high", 0.0), t.optDouble("veryHigh", 0.0), t.getDouble("coverage"))
                val spec = RingSpec(86400, range = if (c.getString("range") == "tight") TirRange.TIGHT else TirRange.IN_RANGE)
                val e = c.getJSONObject("expect")
                val col = GlucoseGraph.pct3(tir.below, tir.inRange, tir.above)
                val want = e.getJSONArray("column")
                val got = listOf(
                    "mark" to (TirMark.of(tir, spec.range).name.lowercase() to e.getString("mark")),
                    "text" to (TirRing.text(tir, spec) to e.getString("text")),
                    "column" to (col.toList().toString() to List(3) { want.getInt(it) }.toString()),
                    "crown" to (TirMark.column(tir).toString() to e.getBoolean("crown").toString()),
                    "flower" to ((Bloom.of(tir, spec.range)?.name?.lowercase() ?: "none") to e.getString("flower")),
                )
                for ((k, gw) in got) if (gw.first != gw.second) fails += "${f.name}: ${c.getString("name")}: $k ${gw.first} ≠ ${gw.second}"
                n++
            }
        }
        assertTrue("mark fixtures: $n cases, failed:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun sensor() {
        val files = File(dir(), "sensor").listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertTrue("no sensor fixtures", files.isNotEmpty())
        var n = 0
        val fails = ArrayList<String>()
        fun near(a: Double?, b: Double?) = if (a == null || b == null) a == b else kotlin.math.abs(a - b) <= 0.001
        for (f in files) {
            val root = JSONObject(f.readText())
            val cases = root.optJSONArray("cases") ?: continue
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val start = now + c.getLong("start")
                val info = app.mauri.sukrara.core.SensorInfo("", start, now + c.getLong("end"),
                    if (c.has("warmup")) now + c.getLong("warmup") else start + 3_600)
                val latest = if (c.isNull("latest")) null else now + c.getLong("latest")
                val want = c.getJSONObject("expect")
                val look = SensorBadge.look(info, now, latest, app.mauri.sukrara.core.Words())!!
                val ring = SensorBadge.ring(look, info, now, c.getInt("countdownHours"))
                val wantRing = if (want.isNull("ring")) null else want.getDouble("ring")
                val wantWarm = if (want.isNull("warmup")) null else want.getString("warmup")
                val pill = if (look.ended) StatusBadge.replaceSensor(app.mauri.sukrara.core.Words()) else null
                val wantPill = want.optJSONObject("pill")?.let { StatusBadge.Look(it.getString("kind"), it.getString("text"), it.getString("short"), it.getBoolean("tonal")) }
                val ok = pill == wantPill && look.label == want.getString("label") && look.ended == want.getBoolean("ended") &&
                    near(look.life.toDouble(), want.getDouble("life")) && look.warmup == wantWarm && near(ring, wantRing)
                if (!ok) fails += "${f.name}: ${c.getString("name")}\n  want $want\n  got  label=${look.label} ended=${look.ended} life=${look.life} warmup=${look.warmup} ring=$ring"
                n++
            }
        }
        assertTrue("sensor fixtures failed:\n" + fails.joinToString("\n"), fails.isEmpty())
        println("sensor fixtures: $n cases passed")
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
