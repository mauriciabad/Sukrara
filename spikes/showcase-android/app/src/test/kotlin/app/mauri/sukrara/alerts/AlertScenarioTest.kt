// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.treatments.Treatment
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.json.JSONObject
import java.io.File

/**
 * The shared alert timelines (spikes/alerts/scenarios/\*.txt, format and driver in spikes/alerts/SPEC.md §7) run on
 * the real [AlertEngine]: every minute's fired headlines and clears must match exactly, in order.
 */
@RunWith(Parameterized::class)
class AlertScenarioTest(private val name: String, private val sc: AlertScenarios.Scenario) {
    @Test fun timeline() {
        val errors = AlertScenarios.run(sc)
        if (errors.isNotEmpty()) fail("${sc.file}: ${sc.title}\n" + errors.joinToString("\n"))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = AlertScenarios.load().also { assertTrue("no scenarios found", it.isNotEmpty()) }
            .map { arrayOf("${it.file}: ${it.title}", it) }
    }
}

object AlertScenarios {
    data class Line(val minute: Int, val at: Int, val events: List<String>, val expect: List<String>, val src: String)
    data class Scenario(val file: String, val title: String, val settings: List<String>, val lines: List<Line>, val end: Int)

    private const val T0 = 1_760_000_040L   // a whole minute

    fun dir(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "spikes/alerts/scenarios").takeIf { d -> d.isDirectory } ?: File(it, "alerts/scenarios") }
        .first { it.isDirectory }

    fun load(): List<Scenario> = dir().listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }.flatMap { parse(it.name, it.readText()) }

    fun parse(file: String, text: String): List<Scenario> {
        val out = mutableListOf<Scenario>()
        var title: String? = null
        var settings = emptyList<String>()
        var lines = mutableListOf<Line>()
        var end: Int? = null
        fun flush() {
            val t = title ?: return
            out += Scenario(file, t, settings, lines, end ?: lines.maxOf { it.minute })
            title = null; settings = emptyList(); lines = mutableListOf(); end = null
        }
        for (raw in text.lines()) {
            val l = raw.substringBefore('#').trim()
            if (l.isEmpty()) continue
            when {
                l.startsWith("scenario ") -> { flush(); title = l.removePrefix("scenario ").trim() }
                l.startsWith("settings ") -> settings = l.removePrefix("settings ").trim().split(Regex("\\s+"))
                l.startsWith("end ") -> end = l.removePrefix("end ").trim().toInt()
                else -> {
                    val (left, right) = if ("=>" in l) l.substringBefore("=>") to l.substringAfter("=>") else l to ""
                    val time = left.trim().substringBefore(' ')
                    val at = time.split(':').let { it[0].toInt() * 60 + (it.getOrNull(1)?.toInt() ?: 0) }
                    val events = left.trim().removePrefix(time).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val expect = right.split(';').map { it.trim() }.filter { it.isNotEmpty() && it != "nothing" }
                    require(lines.none { it.minute == at / 60 }) { "$file: two lines for minute ${at / 60}" }
                    lines += Line(at / 60, at, events, expect, raw)
                }
            }
        }
        flush()
        return out
    }

    /** One `key=value` of a `settings` line or `set` event on top of [s]. */
    fun set(s: AlertSettings, kv: String): AlertSettings {
        val (k, v) = kv.split('=', limit = 2).let { it[0] to it[1] }
        fun min() = v.toInt()
        return when (k) {
            "alerts" -> s.copy(on = v == "on")
            "carbs.mute" -> if (v == "off") s.copy(muteCarbs = false) else s.copy(muteCarbs = true, muteCarbsMin = min())
            "insulin.mute" -> if (v == "off") s.copy(muteInsulin = false) else s.copy(muteInsulin = true, muteInsulinMin = min())
            "mute.min" -> s.copy(actionMuteMin = min())
            "slow.min" -> s.copy(slowMin = min())
            "falling.mute" -> if (v == "off") s.copy(muteFalling = false) else s.copy(muteFalling = true, muteFallingMin = min())
            else -> {
                val kind = k.substringBefore('.')
                require(kind in Kind.ALL) { "unknown setting $kv" }
                val c = s.cfg(kind)
                val n = when (k.substringAfter('.', "")) {
                    "" -> when (v) { "on" -> c.copy(on = true); "off" -> c.copy(on = false); else -> c.copy(value = v.toDouble()) }
                    "repeat" -> c.copy(repeatMin = min())
                    else -> error("unknown setting $kv")
                }
                s.copy(kinds = s.kinds + (kind to n))
            }
        }
    }

    fun describe(a: AlertEngine.Action) = when (a) {
        is AlertEngine.Fire -> Kind.message(a.kind, a.treated)
        is AlertEngine.Clear -> "clear ${a.kind}"
    }

    /** Runs [sc] (driver: SPEC.md §7); returns one line per minute that differs, empty when it all matches. */
    fun run(sc: Scenario): List<String> {
        var s = sc.settings.fold(AlertSettings()) { acc, kv -> set(acc, kv) }
        var e = AlertEngine()
        val trs = mutableListOf<Treatment>()
        val got = HashMap<Int, MutableList<String>>()
        var glucose = 0
        var rate = 0.0
        var age = 0
        var sensor = true
        var every = 1
        var nextReading: Long? = null
        var timer: Long? = null
        val byTime = sc.lines.associateBy { T0 + it.at }
        val stop = T0 + (sc.end + 1) * 60L
        var t = T0
        fun record(acts: List<AlertEngine.Action>) =
            acts.forEach { got.getOrPut(((t - T0) / 60).toInt()) { mutableListOf() } += describe(it) }
        fun check() = record(e.evaluate(s, AlertEngine.doses(trs), t))
        while (t < stop) {
            byTime[t]?.events?.forEach { ev ->
                val w = ev.split(Regex("\\s+"))
                when (w[0]) {
                    "glucose" -> {
                        glucose = w[1].toInt()
                        rate = w.indexOf("rate").takeIf { it > 0 }?.let { w[it + 1].toDouble() } ?: 0.0
                        age = w.indexOf("age").takeIf { it > 0 }?.let { w[it + 1].toInt() } ?: 0
                        if (nextReading == null) nextReading = t
                    }
                    "readings" -> every = w[2].toInt()
                    "sensor" -> when (w[1]) {
                        // round sensor-life: the sensor ends now (no more readings) / a new one starts now (60 min warm-up)
                        "ended" -> { sensor = false; e.sensor = SensorInfo("s", t - 15 * 86_400L, t); check() }
                        "started" -> { sensor = false; e.sensor = SensorLife.fromStart("s2", t, 0); check() }
                        else -> { sensor = w[1] == "on"; if (sensor) nextReading = t }
                    }
                    "carbs", "insulin" -> {
                        val k = if (w[0] == "carbs") "carbs" else "rapid"
                        trs += Treatment(id = "${k}_$t", k = k, a = w[1].toDouble(), t = t, u = t * 1000, o = "phone")
                        check()
                    }
                    "delete" -> {
                        val k = if (w[1] == "carbs") "carbs" else "rapid"
                        val i = trs.indexOfLast { it.k == k && !it.del }
                        trs[i] = trs[i].copy(del = true)
                        check()
                    }
                    // the alert notification's actions: Mute (the setting's minutes, or typed ones), Slow down
                    "mute" -> record(e.mute(w[1], w.getOrNull(2)?.toInt() ?: s.actionMuteMin, s, AlertEngine.doses(trs), t))
                    "slow" -> record(e.slow(w[1], s, AlertEngine.doses(trs), t))
                    "set" -> { s = w.drop(1).fold(s) { acc, kv -> set(acc, kv) }; check() }
                    "restart" -> {
                        val saved = JSONObject(e.snapshot().toString())
                        val last = e.last
                        val si = e.sensor
                        e = AlertEngine()
                        e.seed(last)
                        e.sensor = si
                        e.restore(saved, s, t).forEach { got.getOrPut(((t - T0) / 60).toInt()) { mutableListOf() } += "resume ${it.kind}" }
                    }
                    else -> error("unknown event: $ev")
                }
            }
            val reading = nextReading
            if (sensor && reading != null && reading <= t) {
                record(e.reading(Reading(t - age * 60L, glucose, rate), s, AlertEngine.doses(trs), t))
                nextReading = t + every * 60L
            } else if (timer != null && timer <= t) check()
            timer = e.nextCheckAt(s, t)
            t = listOfNotNull(byTime.keys.filter { it > t }.minOrNull(), nextReading?.takeIf { sensor }, timer).minOrNull() ?: break
        }
        val expected = sc.lines.associate { it.minute to it.expect }
        return (0..sc.end).mapNotNull { m ->
            val want = expected[m] ?: emptyList()
            val have = got[m] ?: emptyList()
            if (want == have) null else "minute $m: expected ${want.ifEmpty { listOf("nothing") }}, got ${have.ifEmpty { listOf("nothing") }}"
        }
    }
}
