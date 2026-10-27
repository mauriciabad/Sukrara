// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.testing.Contracts
import java.io.File

/**
 * The shared alert timelines (contracts/alerts/scenarios/\*.txt; format and driver in contracts/alerts/SPEC.md §7) run
 * through [AlertMachine]: the wearer's machine, and for `mirror` scenarios a follower machine mirroring it fed with the
 * wearer's [AlertEffect.Mirror] events. Strict: an unknown line, event or setting fails the scenario.
 */
object AlertScenarios {
    data class Line(val minute: Int, val at: Int, val events: List<String>, val expect: List<String>)

    data class Scenario(
        val file: String,
        val title: String,
        val settings: List<String>,
        val lines: List<Line>,
        val end: Int,
        val mirror: Boolean,
    )

    /** A whole minute. */
    private const val T0 = 1_760_000_040L

    fun dir(): File = Contracts.root.resolve("alerts/scenarios")

    fun load(): List<Scenario> {
        val files = dir().listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
        check(files.isNotEmpty()) { "no scenario files in ${dir()}" }
        return files.flatMap { f -> parse(f.name, f.readText()).also { check(it.isNotEmpty()) { "${f.name}: no scenarios" } } }
    }

    fun parse(file: String, text: String): List<Scenario> {
        val out = mutableListOf<Scenario>()
        var title: String? = null
        var settings = emptyList<String>()
        var lines = mutableListOf<Line>()
        var end: Int? = null
        var mirror = false
        fun flush() {
            val t = title ?: return
            out += Scenario(file, t, settings, lines, end ?: lines.maxOf { it.minute }, mirror)
            title = null; settings = emptyList(); lines = mutableListOf(); end = null; mirror = false
        }
        for (raw in text.lines()) {
            val l = raw.substringBefore('#').trim()
            if (l.isEmpty()) continue
            when {
                l.startsWith("scenario ") -> { flush(); title = l.removePrefix("scenario ").trim() }
                l.startsWith("settings ") -> settings = l.removePrefix("settings ").trim().split(Regex("\\s+"))
                l.startsWith("end ") -> end = l.removePrefix("end ").trim().toInt()
                l == "mirror" -> mirror = true
                else -> {
                    requireNotNull(title) { "$file: a line before any scenario: $raw" }
                    val (left, right) = if ("=>" in l) l.substringBefore("=>") to l.substringAfter("=>") else l to ""
                    val time = left.trim().substringBefore(' ')
                    require(time.matches(Regex("""\d+(:\d{2})?"""))) { "$file: not a time: $raw" }
                    val at = time.split(':').let { it[0].toInt() * 60 + (it.getOrNull(1)?.toInt() ?: 0) }
                    val events = left.trim().removePrefix(time).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val expect = right.split(';').map { it.trim() }.filter { it.isNotEmpty() && it != "nothing" }
                    require(lines.none { it.minute == at / 60 }) { "$file: two lines for minute ${at / 60}" }
                    lines += Line(at / 60, at, events, expect)
                }
            }
        }
        flush()
        return out
    }

    /** One `key=value` of a `settings` line or `set` event on top of [s]. */
    fun set(s: AlertSettings, kv: String): AlertSettings {
        val (k, v) = kv.split('=', limit = 2).also { require(it.size == 2) { "not key=value: $kv" } }.let { it[0] to it[1] }
        fun quiet(q: QuietRule) = if (v == "off") q.copy(on = false) else QuietRule(on = true, min = v.toInt())
        return when (k) {
            "alerts" -> s.copy(on = v == "on")
            "carbs.mute" -> s.copy(afterCarbs = quiet(s.afterCarbs))
            "insulin.mute" -> s.copy(afterInsulin = quiet(s.afterInsulin))
            "falling.mute" -> s.copy(fallingHigh = quiet(s.fallingHigh))
            "mute.min" -> s.copy(actions = s.actions.copy(muteMin = v.toInt()))
            "relax.min" -> s.copy(actions = s.actions.copy(relaxMin = v.toInt()))
            "back.mgdl" -> s.copy(actions = s.actions.copy(backMgdl = v.toInt()))
            "back.min" -> s.copy(actions = s.actions.copy(backMin = v.toInt()))
            else -> {
                val kind = requireNotNull(AlertKind.fromWire(k.substringBefore('.'))) { "unknown setting $kv" }
                val r = s.rule(kind)
                s.withRule(
                    kind,
                    when (k.substringAfter('.', "")) {
                        "" -> when (v) { "on" -> r.copy(on = true); "off" -> r.copy(on = false); else -> r.copy(threshold = v.toDouble()) }
                        "repeat" -> r.copy(repeatMin = v.toInt())
                        else -> error("unknown setting $kv")
                    },
                )
            }
        }
    }

    /** A scenario's length ("30", "2h", "1 h 30", "1:30") in minutes. */
    fun minutes(text: String): Int {
        val t = text.trim().lowercase()
        Regex("""(\d+):(\d{2})""").matchEntire(t)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val m = requireNotNull(Regex("""(?:(\d+)\s*h)?\s*(?:(\d+)\s*(?:min|m)?)?""").matchEntire(t)) { "not a duration: $text" }
        require(t.isNotEmpty()) { "empty duration" }
        return (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
    }

    /** Runs [sc] (driver: SPEC §7); one line per minute that differs, empty when it all matches. */
    fun run(sc: Scenario): List<String> {
        var s = sc.settings.fold(AlertSettings()) { acc, kv -> set(acc, kv) }
        val got = HashMap<Int, MutableList<String>>()
        var t = T0
        fun note(what: String) { got.getOrPut(((t - T0) / 60).toInt()) { mutableListOf() } += what }

        var wearer = AlertMachine(AlertRole.WEARER, s).also { it.start(null, null, null, T0) }
        // the follower's own no_data isn't run (SPEC §7)
        fun followerSettings() = s.withRule(AlertKind.NO_DATA, s.rule(AlertKind.NO_DATA).copy(on = false))
        val follower = AlertMachine(AlertRole.FOLLOWER_MIRROR, followerSettings()).also { it.start(null, null, null, T0) }
        val doses = mutableListOf<Pair<TreatmentKind, Long>>()
        val deleted = mutableSetOf<Int>()
        var sensor: SensorWindow? = null
        var timer: Long? = null

        fun followerSaw(effects: List<AlertEffect>) = effects.forEach { e ->
            when (e) {
                is AlertEffect.Fire -> note("follower ${Headlines.english(e.firing.kind, e.firing.treated)}")
                is AlertEffect.Clear -> note("follower clear ${e.kind.wire}")
                is AlertEffect.Back -> note("follower back ${e.side.wire}")
                is AlertEffect.Mirror -> error("a follower never publishes")
                is AlertEffect.Schedule -> Unit
            }
        }

        fun follow(input: AlertInput) { if (sc.mirror) followerSaw(follower.handle(input, t)) }

        fun wearerDid(effects: List<AlertEffect>) = effects.forEach { e ->
            when (e) {
                is AlertEffect.Fire -> note(if (e.silent) "resume ${e.firing.kind.wire}" else Headlines.english(e.firing.kind, e.firing.treated))
                is AlertEffect.Clear -> note("clear ${e.kind.wire}")
                is AlertEffect.Back -> note("back ${e.side.wire}")
                is AlertEffect.Mirror -> follow(AlertInput.Remote(e.event))
                is AlertEffect.Schedule -> timer = e.at
            }
        }

        fun doses() = Doses(
            carbs = doses.filterIndexed { i, d -> i !in deleted && d.first == TreatmentKind.CARBS }.map { it.second },
            rapid = doses.filterIndexed { i, d -> i !in deleted && d.first == TreatmentKind.RAPID }.map { it.second },
        )

        var glucose = 0
        var rate = 0.0
        var age = 0
        var sensorOn = true
        var every = 1
        var nextReading: Long? = null
        val byTime = sc.lines.associateBy { T0 + it.at }
        val stop = T0 + (sc.end + 1) * 60L
        fun allEnd(w: List<String>, from: Int): Long =
            if (w.getOrNull(from) == "never") Rules.NEVER else t + minutes(w.drop(from).joinToString(" ")) * 60L
        fun kind(word: String) = requireNotNull(AlertKind.fromWire(word)) { "unknown kind $word" }
        fun length(w: List<String>, from: Int) = w.drop(from).joinToString(" ").takeIf { it.isNotBlank() }?.let(::minutes) ?: s.actions.muteMin

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
                        "ended" -> { sensorOn = false; sensor = SensorWindow(t - 15 * 86_400L, t, t - 15 * 86_400L + 3600); wearerDid(wearer.handle(AlertInput.Sensor(sensor), t)) }
                        "started" -> { sensorOn = false; sensor = SensorWindow.started(t, 15 * 1440); wearerDid(wearer.handle(AlertInput.Sensor(sensor), t)) }
                        "on", "off" -> { sensorOn = w[1] == "on"; if (sensorOn) nextReading = t }
                        else -> error("unknown event: $ev")
                    }
                    "carbs", "insulin" -> {
                        doses += (if (w[0] == "carbs") TreatmentKind.CARBS else TreatmentKind.RAPID) to t
                        wearerDid(wearer.handle(AlertInput.Treatments(doses()), t))
                    }
                    "delete" -> {
                        val k = if (w[1] == "carbs") TreatmentKind.CARBS else TreatmentKind.RAPID
                        deleted += doses.indices.last { it !in deleted && doses[it].first == k }
                        wearerDid(wearer.handle(AlertInput.Treatments(doses()), t))
                    }
                    "mute" -> wearerDid(
                        wearer.handle(if (w[1] == "all") AlertInput.MuteAll(allEnd(w, 2)) else AlertInput.Mute(kind(w[1]), length(w, 2)), t),
                    )
                    "relax" -> wearerDid(wearer.handle(AlertInput.RemindLess(kind(w[1])), t))
                    "unmute" -> wearerDid(wearer.handle(if (w[1] == "all") AlertInput.UnmuteAll else AlertInput.Unmute(kind(w[1])), t))
                    "unrelax" -> wearerDid(wearer.handle(AlertInput.RemindAsUsual(kind(w[1])), t))
                    "follower" -> {
                        require(sc.mirror) { "follower event without mirror: $ev" }
                        follow(
                            when (w[1]) {
                                "mute" -> if (w[2] == "all") AlertInput.MuteAll(allEnd(w, 3)) else AlertInput.Mute(kind(w[2]), length(w, 3))
                                "relax" -> AlertInput.RemindLess(kind(w[2]))
                                "unmute" -> if (w[2] == "all") AlertInput.UnmuteAll else AlertInput.Unmute(kind(w[2]))
                                "unrelax" -> AlertInput.RemindAsUsual(kind(w[2]))
                                else -> error("unknown event: $ev")
                            },
                        )
                    }
                    "set" -> {
                        s = w.drop(1).fold(s) { acc, kv -> set(acc, kv) }
                        follow(AlertInput.Settings(followerSettings()))
                        wearerDid(wearer.handle(AlertInput.Settings(s), t))
                    }
                    "restart" -> {
                        // the process restarts: a new machine restores the saved state (through its JSON) and the last reading
                        val saved = AlertStateCodec.decode(AlertStateCodec.encode(wearer.snapshot()))
                        val last = wearer.engine.last
                        wearer = AlertMachine(AlertRole.WEARER, s, doses())
                        wearerDid(wearer.start(saved, last, sensor, t))
                    }
                    else -> error("unknown event: $ev")
                }
            }
            val due = nextReading
            if (sensorOn && due != null && due <= t) {
                val r = AlertReading(t - age * 60L, glucose, rate)
                wearerDid(wearer.handle(AlertInput.Reading(r), t))
                follow(AlertInput.Reading(r))
                nextReading = t + every * 60L
            } else if (timer.let { it != null && it <= t }) {
                wearerDid(wearer.handle(AlertInput.Tick, t))
                follow(AlertInput.Tick)
            }
            t = listOfNotNull(byTime.keys.filter { it > t }.minOrNull(), nextReading?.takeIf { sensorOn }, timer).filter { it > t }.minOrNull() ?: break
        }
        // the wearer's and the follower's actions are compared apart (their order within a minute is free)
        fun split(l: List<String>) = l.partition { !it.startsWith("follower ") }.let { (w, f) -> w + f }
        val expected = sc.lines.associate { it.minute to it.expect }
        return (0..sc.end).mapNotNull { m ->
            val want = split(expected[m].orEmpty())
            val have = split(got[m].orEmpty())
            if (want == have) null else "minute $m: expected ${want.ifEmpty { listOf("nothing") }}, got ${have.ifEmpty { listOf("nothing") }}"
        }
    }
}

/** SPEC §1's English headlines (the apps word them from their own strings; the scenarios use these). */
object Headlines {
    private fun label(k: AlertKind) = when (k) {
        AlertKind.VERY_LOW -> "Very low"
        AlertKind.LOW -> "Low"
        AlertKind.HIGH -> "High"
        AlertKind.VERY_HIGH -> "Very high"
        AlertKind.EXTREMELY_HIGH -> "Extremely high"
        AlertKind.FALLING_FAST -> "Falling fast"
        AlertKind.RISING_FAST -> "Rising fast"
        AlertKind.NO_DATA -> "No data"
    }

    fun english(k: AlertKind, treated: Int): String {
        val base = when {
            k.isLevel -> "${label(k)} glucose"
            k == AlertKind.NO_DATA -> "No glucose data"
            else -> label(k)
        }
        val more = when {
            treated <= 0 || !k.isLevel -> null
            k.side == Side.LOW -> if (treated == 1) "eat more" else "eat more again"
            else -> if (treated == 1) "still high after insulin" else "still high after more insulin"
        }
        return if (more == null) base else "$base · $more"
    }
}
