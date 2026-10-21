// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Spanned
import android.text.style.StrikethroughSpan
import app.mauri.sukrara.R
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.hook.HookText
import app.mauri.sukrara.status.FreshnessTicker
import app.mauri.sukrara.status.GlucoseNotification
import app.mauri.sukrara.status.RootStatusBar
import app.mauri.sukrara.ui.TreatmentTileService
import app.mauri.sukrara.widgets.valueWords
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The freshness steps (spikes/graph-fixtures/freshness/freshness.json, the file the Mac's `-graphselftest` runs too):
 * the shared [Freshness.look] at every step boundary, and every Android surface in the file's registry rendered through
 * its own code at each case (the card on a recording canvas, the notification, the status-bar icons and hook text, the
 * Quick Settings tile, the widgets' descriptions). A surface listed there without an adapter here fails, so a new
 * surface can't skip the steps. [night] replays 2026-10-20's outage with a fake clock: the alarms land on the steps and
 * every surface changes at them without a new reading.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class FreshnessFixtureTest {
    private val now = 1_800_000_000L
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        RoundedFont.init(ctx)
        app.mauri.sukrara.core.Words.loader = { app.mauri.sukrara.core.Words.of(ctx.resources) }
    }

    private fun root(): JSONObject = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "spikes/graph-fixtures/freshness/freshness.json") }.first { it.isFile }.let { JSONObject(it.readText()) }

    private class Case(val name: String, val age: Long?, val sgv: Int?, val rate: Double?, val ended: Boolean, val e: JSONObject) {
        val state get() = e.getString("state")
        val text: String? get() = if (e.isNull("text")) null else e.getString("text")
        val strike get() = e.getBoolean("strike")
        val trend: String? get() = if (e.isNull("trend")) null else e.getString("trend")
        val time get() = e.getBoolean("time")
        val graph get() = e.getBoolean("graph")
    }

    private fun cases(): List<Case> = root().getJSONArray("cases").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Case(it.getString("name"), if (it.isNull("age")) null else it.getLong("age"), if (it.isNull("sgv")) null else it.getInt("sgv"),
                if (it.isNull("rate")) null else it.getDouble("rate"), it.optBoolean("ended", false), it.getJSONObject("expect"))
        }
    }

    private fun look(c: Case) = Freshness.look(c.sgv, c.age?.let { now - it }, c.rate, now, c.ended)
    private fun reading(c: Case) = if (c.sgv != null && c.age != null) Reading(now - c.age, c.sgv, c.rate) else null

    @Test fun look() {
        for (c in cases()) {
            val l = look(c)
            val got = "${l.state.name.lowercase()} ${l.text} ${l.strike} ${l.trend?.name?.lowercase()} ${l.fresh.showTime} ${l.fresh.showGraph}"
            val want = "${c.state} ${c.text} ${c.strike} ${c.trend} ${c.time} ${c.graph}"
            assertEquals(c.name, want, got)
            assertEquals("${c.name}: grey = strike", c.strike, l.grey)
        }
    }

    @Test fun steps() {
        val s = root().getJSONObject("steps")
        val from = s.getJSONArray("from"); val next = s.getJSONArray("next")
        for (i in 0 until from.length()) {
            val want = if (next.isNull(i)) null else next.getLong(i)
            assertEquals("step after ${from.getLong(i)} s", want, Freshness.nextChangeAt(0, from.getLong(i)))
            assertEquals("ticker after ${from.getLong(i)} s", want?.plus(now), FreshnessTicker.next(now, now + from.getLong(i)))
        }
    }

    // ------------------------------------------------------------------------------------------- surfaces

    /** One adapter per surface id in the fixture's registry: what that surface draws / says for a case, checked against it. */
    private val adapters: Map<String, (Case) -> List<String>> = mapOf(
        "card" to ::card,
        "app-panel" to ::panel,
        "widget-description" to { c -> description(c, withTime = true) },
        "lock-widget-description" to { c -> description(c, withTime = false) },
        "notification" to ::notification,
        "status-icons" to ::statusIcons,
        "status-hook" to ::statusHook,
        "quick-tile" to ::tile,
    )

    @Test fun surfaces() {
        val registry = root().getJSONObject("surfaces").getJSONObject("android")
        val ids = registry.keys().asSequence().toSet()
        assertEquals("every Android surface in freshness.json has an adapter here (and no adapter without a surface)", ids, adapters.keys)
        val failures = mutableListOf<String>()
        for (c in cases()) for ((id, adapter) in adapters) adapter(c).forEach { failures += "$id · ${c.name}: $it" }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun check(out: MutableList<String>, what: String, want: Any?, got: Any?) { if (want != got) out += "$what: want $want, got $got" }

    /** A canvas that remembers what was drawn: text (with its strike flag), stroked circles (the "no trend" ring), paths. */
    private class Recorder(bmp: Bitmap) : Canvas(bmp) {
        val texts = mutableListOf<Pair<String, Boolean>>()
        val below = mutableListOf<String>()   // text drawn under the value row (y > 150 px): the chart's labels
        var rings = 0
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += text to paint.isStrikeThruText; if (y > 150) below += text; super.drawText(text, x, y, paint)
        }
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
            if (paint.style == Paint.Style.STROKE) rings++; super.drawCircle(cx, cy, radius, paint)
        }
        override fun drawPath(path: Path, paint: Paint) = super.drawPath(path, paint)
    }

    private fun sensor(c: Case) = if (c.ended) SensorInfo("S", now - 15 * 86400 - 3600, now - 3600) else null

    /** The card (widgets, lock screen, notification art, Mac via its own adapter): the value row and the chart's place. */
    private fun card(c: Case): List<String> {
        val out = mutableListOf<String>()
        val r = reading(c)
        // an hour of history before the latest reading, so a chart has something to draw
        val history = r?.let { l -> (60 downTo 1).map { Reading(l.t - it * 60L, 120, 0.0) } + l } ?: emptyList()
        val cv = Recorder(Bitmap.createBitmap(720, 400, Bitmap.Config.ARGB_8888))
        val st = CardStyle(graph = GraphStyle(), rings = emptyList(), sensorTime = false)
        GlucoseCard.draw(cv, 720f, 400f, history, emptyList(), now, st,
            GraphPalette.DARK, 2f, alerts = null, sensor = sensor(c), countdownH = 8, paused = emptyMap())
        val w = app.mauri.sukrara.core.Words.current
        when (c.state) {
            "value" -> {
                check(out, "value drawn (strike)", listOf(c.text to c.strike), cv.texts.filter { it.first == c.text })
                check(out, "no-trend ring", c.trend == "none", cv.rings > 0)
            }
            "no_readings" -> {
                check(out, "says no reading since", true, cv.texts.any { it.first == w.noReadingSince })
                check(out, "no value", false, cv.texts.any { it.first == "${c.sgv}" })
            }
            "none" -> check(out, "says no readings yet", true, cv.texts.any { it.first == w.noReadingsYet })
            "ended" -> check(out, "says sensor ended", true, cv.texts.any { it.first == w.sensorEnded })
        }
        val time = r?.let { Freshness.timeText(it.t, now, st.hour24) }
        if (c.state == "value") check(out, "the reading's time", c.time, cv.texts.any { it.first == time })
        // the chart: its labels under the value row (without one the value's words fill the card)
        if (c.state != "ended") check(out, "chart", c.graph, cv.below.any { it !in setOf(w.noReadingSince, w.noReadingsYet, time) })
        return out
    }

    /** The app's home panel decides through the same look (its Compose value and the card's chart part). */
    private fun panel(c: Case): List<String> {
        // GlucosePanel: number = look.text while VALUE, the arrow only for TrendMark.ARROW, chart while fresh.showGraph.
        // Its drawing is Compose; its decisions are this look (tools/check freshness-surface keeps it so) and its chart
        // is the card's GRAPH part, covered by [card].
        val l = look(c)
        val out = mutableListOf<String>()
        check(out, "number", c.text.takeIf { c.state == "value" }, if (l.state == GlucoseLook.State.VALUE) l.text else null)
        return out
    }

    private fun description(c: Case, withTime: Boolean): List<String> {
        val out = mutableListOf<String>()
        val r = reading(c)
        val s = valueWords(ctx, look(c), r, now, withTime)
        when (c.state) {
            "value" -> {
                check(out, "says the value", true, s.contains(c.text!!))
                check(out, "says old", c.strike, s.contains(ctx.getString(R.string.notif_value_old, c.text)))
                check(out, "trend unknown", c.trend == "none", s.contains(ctx.getString(R.string.trend_unknown)))
                check(out, "time", c.time && withTime, s.contains(Freshness.timeText(r!!.t, now)))
            }
            "no_readings" -> check(out, "no reading since", ctx.getString(R.string.widget_desc_no_reading_since, Freshness.timeText(r!!.t, now)), s)
            "none" -> check(out, "no readings yet", ctx.getString(R.string.widget_desc_no_readings), s)
        }
        return out
    }

    private fun notification(c: Case): List<String> {
        val out = mutableListOf<String>()
        val l = look(c)
        check(out, "small icon", when (c.state) {
            "no_readings" -> R.drawable.ic_stat_no_readings
            "ended" -> R.drawable.ic_stat_sensor_ended
            else -> R.drawable.ic_stat_sukrara
        }, GlucoseNotification.smallIcon(l))
        if (c.state == "value") {
            val t = GlucoseNotification.title(l, c.rate)
            check(out, "title value", c.text, t.toString().substringBefore(" "))
            check(out, "title struck", c.strike, (t as Spanned).getSpans(0, t.length, StrikethroughSpan::class.java).isNotEmpty())
            check(out, "title no-trend ring", c.trend == "none", t.endsWith(TrendMark.RING))
            check(out, "chip text", if (c.strike) c.text!!.map { "${it}\u0336" }.joinToString("") else c.text, l.plain)
            // the number icon (setting "icon"): half strength when struck
            val bmp = GlucoseNotification.iconBitmap(l, c.rate)
            val maxA = (0 until bmp.height step 4).maxOf { y -> (0 until bmp.width step 4).maxOf { x -> bmp.getPixel(x, y) ushr 24 } }
            check(out, "number icon half strength", c.strike, maxA in 1..140)
        }
        return out
    }

    private fun statusIcons(c: Case): List<String> {
        val out = mutableListOf<String>()
        val p = RootStatusBar.iconPlan(look(c), reading(c))
        when (c.state) {
            "value" -> {
                val v = when (c.text) { "LO" -> "lo"; "HI" -> "hi"; else -> c.text }
                check(out, "number slot", if (c.strike) "sb_s$v" else if (v == "lo" || v == "hi") "sb_$v" else "sb_n$v", p.number)
                check(out, "trend slot", when {
                    c.trend == "arrow" -> app.mauri.sukrara.core.Trend.drawableName(c.rate!!)
                    c.strike -> "sb_t_none_s"
                    else -> "sb_t_none"
                }, p.trend)
                // every name resolves to a drawable (looked up by name at run time)
                for (n in listOfNotNull(p.number, p.trend))
                    check(out, "drawable $n exists", true, ctx.resources.getIdentifier(n, "drawable", ctx.packageName) != 0)
            }
            "no_readings" -> check(out, "slots", "ic_stat_no_readings null", "${p.trend} ${p.number}")
            "ended" -> check(out, "slots", "ic_stat_sensor_ended null", "${p.trend} ${p.number}")
            "none" -> check(out, "slots", "null null", "${p.trend} ${p.number}")
        }
        return out
    }

    private fun statusHook(c: Case): List<String> {
        val out = mutableListOf<String>()
        val red = 0xFFFF0000.toInt(); val base = 0xFFFFFFFF.toInt()
        val v = HookText.of(look(c), true, red, base)
        when (c.state) {
            "value" -> {
                check(out, "text", c.text, v.text)
                check(out, "strike", c.strike, v.strike)
                check(out, "grey (base colour, half alpha)", c.strike, v.color == base && v.alpha == 0.5f)
                check(out, "mark", if (c.trend == "arrow") HookText.Mark.ARROW else HookText.Mark.RING, v.mark)
            }
            "no_readings", "none" -> check(out, "no value, the no-readings icon", " ${HookText.Mark.NO_READINGS}", "${v.text} ${v.mark}")
            "ended" -> check(out, "the flag", " ${HookText.Mark.FLAG}", "${v.text} ${v.mark}")
        }
        return out
    }

    private fun tile(c: Case): List<String> {
        val out = mutableListOf<String>()
        val s = TreatmentTileService.subtitle(look(c), c.rate) { st -> st.name }
        when (c.state) {
            "value" -> check(out, "subtitle", (if (c.strike) c.text!!.map { "${it}\u0336" }.joinToString("") else c.text) + " " +
                (if (c.trend == "arrow") app.mauri.sukrara.core.Trend.arrow(c.rate) else TrendMark.RING), s)
            "no_readings" -> check(out, "subtitle", "NO_READINGS", s)
            "ended" -> check(out, "subtitle", "ENDED", s)
            "none" -> check(out, "subtitle", null, s)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------- the night

    /**
     * 2026-10-20/21 on the user's phone: the last reading at 00:32:21 (77 mg/dL, falling), the next at 00:53:25. With
     * nothing in between, the alarms must land on the steps and every surface must change there by itself.
     */
    @Test fun night() {
        val last = Reading(now, 77, -1.0)
        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
        val seen = mutableListOf<String>()
        var clock = now + 1
        var renders = 0
        FreshnessTicker.onStep = { renders++ }
        FreshnessTicker.plan(ctx, last.t, clock)
        repeat(6) {
            val alarm = shadowOf(am).nextScheduledAlarm ?: return@repeat
            clock = alarm.triggerAtTime / 1000
            // the alarm is FreshnessTicker's receiver, non-waking and exact; deliver it (render, widgets, re-plan)
            assertEquals(FreshnessTicker.Receiver::class.java.name, shadowOf(alarm.operation!!).savedIntent.component?.className)
            assertEquals("non-waking", android.app.AlarmManager.RTC, alarm.type)
            shadowOf(am).scheduledAlarms.forEach { a -> a.operation?.let(am::cancel) }
            FreshnessTicker.Receiver().onReceive(ctx, android.content.Intent())
            // the alarm re-plans from the store; in the test the store is empty, so plan from the same reading again
            FreshnessTicker.plan(ctx, last.t, clock)
            val l = Freshness.look(last.sgv, last.t, last.rate, clock)
            val icons = RootStatusBar.iconPlan(l, last)
            seen += "+${clock - now}s ${icons.number} ${icons.trend} ${GlucoseNotification.smallIcon(l) == R.drawable.ic_stat_no_readings}"
        }
        FreshnessTicker.onStep = null
        assertEquals(listOf(
            "+91s sb_n77 sb_t24 false",                 // late: the time shows (cards), the status bar unchanged
            "+301s sb_s77 sb_t_none_s false",            // struck through, grey, no trend
            "+1201s null ic_stat_no_readings true",      // no readings
            "+10801s null ic_stat_no_readings true",     // no chart
        ), seen)
        assertEquals("one redraw per step", 4, renders)
        assertEquals("nothing planned after the last step", null, FreshnessTicker.planned)
    }
}
