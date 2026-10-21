// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.widgets.WidgetKind
import app.mauri.sukrara.widgets.WidgetUpdater
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * "One graph everywhere", tested (the user, 2026-10-21: "I want to ensure things like this cannot happen again"):
 * every registered surface ([CardSurface], spikes/graph-fixtures/surfaces/surfaces.json) is rendered through its REAL
 * entry path (the widgets' renderWidget, the panel's PanelCard composable, the lock screen's draw, the notification's
 * paintArt and build, the status bar's chipFace, the first reading's drawFirstReading) at each of its sizes, with every
 * scene and settings variant of spikes/graph-fixtures/layout/scenes.json. [GlucoseCard] reports what it laid out
 * ([CardLayout]); the rules in layout/rules.json say what each element may depend on, and the surface is never one of
 * the inputs, so an element laid out differently on one surface fails here. The shared rules' answers are
 * layout/expect.json, which the Mac's `-graphselftest` checks its own surfaces against: Android and Mac can't drift
 * apart either. `-Dlayout.write=true` rewrites expect.json (review the diff: it is the cross-platform contract).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h900dp-xxhdpi")
class CardLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun dir(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "spikes/graph-fixtures") }.first { it.isDirectory }

    private class Scene(val id: String, val readings: List<Reading>, val treatments: List<Treatment>, val sensor: SensorInfo?)

    /** Everything one surface drew for one scene: its card layouts (one per part) and the faces it asked for. */
    private class Render(val surface: CardSurface, val size: String, val scene: String, val settings: String,
                         val layouts: List<CardLayout>, val faces: List<ValueFace>) {
        override fun toString() = "${surface.id} $size"
    }

    private val now by lazy { JSONObject(File(dir(), "layout/scenes.json").readText()).getLong("now") }

    private fun scenes(): List<Scene> {
        val root = JSONObject(File(dir(), "layout/scenes.json").readText())
        val a = root.getJSONArray("scenes")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val days = o.optInt("days", 0)
            val readings = if (days == 0) emptyList() else {
                val lo = o.getInt("lo"); val hi = o.getInt("hi"); val period = o.getLong("period"); val half = period / 2
                val first = now - days * 86400L
                val last = now - o.getLong("age")
                val out = ArrayList<Reading>()
                var t = first
                while (t < last) {
                    val k = Math.floorMod(t - first, period)
                    val tri = if (k <= half) k else period - k
                    out += Reading(t, (lo + (hi - lo) * tri / half).toInt(), null)
                    t += 300
                }
                out += Reading(last, o.getInt("last"), o.getDouble("rate"))
                out
            }
            val trs = o.optJSONArray("treatments")?.let { t ->
                (0 until t.length()).map { t.getJSONObject(it) }.map {
                    Treatment(id = it.getString("id"), k = it.getString("k"), a = it.getDouble("a"), t = now + it.getLong("t"),
                        n = it.optString("n", ""), u = 0, o = "phone")
                }
            } ?: emptyList()
            val sensor = o.optJSONObject("sensor")?.let {
                SensorInfo("S", now + it.getLong("start"), now + it.getLong("end"), now + it.getLong("warmupEnd"))
            }
            Scene(o.getString("id"), readings, trs, sensor)
        }
    }

    private fun settings(): List<Pair<String, (GraphConfig) -> GraphConfig>> {
        val a = JSONObject(File(dir(), "layout/scenes.json").readText()).getJSONArray("settings")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            o.getString("id") to { c: GraphConfig ->
                var x = c
                if (o.has("sensorTime") && !o.getBoolean("sensorTime")) x = x.copy(sensorHidden = GraphPlace.entries.toSet())
                if (o.has("side")) x = x.copy(side = o.getBoolean("side"))
                if (o.has("ringCount")) x = x.copy(ringCount = o.getInt("ringCount"))
                if (o.has("lastPlace")) x = x.copy(lastPlace = LastPlace.valueOf(o.getString("lastPlace")))
                if (o.has("lines")) x = x.copy(lines = o.getJSONArray("lines").let { l -> (0 until l.length()).map(l::getInt) })
                if (o.has("gridHours")) x = x.copy(gridHours = o.getInt("gridHours"))
                x
            }
        }
    }

    private fun sizes(reg: JSONObject, s: CardSurface): List<Pair<Float, Float>> {
        val e = (0 until reg.getJSONArray("surfaces").length()).map { reg.getJSONArray("surfaces").getJSONObject(it) }
            .first { it.getString("id") == s.id }
        val a = e.optJSONArray("sizes") ?: return listOf(0f to 0f)
        return (0 until a.length()).map { a.getJSONArray(it) }.map { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() }
    }

    // ---- the surfaces' real entry paths ------------------------------------------------------------------------

    private val captured = ArrayList<CardLayout>()
    private val faces = ArrayList<ValueFace>()
    private var panel by mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)

    private fun metricsOf(sc: Scene): (RingSpec, Bands) -> GlucoseMetrics = { r, b -> GlucoseMetrics.of(sc.readings, now - r.windowS, now, b) }

    /** Renders [s] through its real entry path; every case of this `when` is a registered surface (exhaustive). */
    private fun render(s: CardSurface, w: Float, h: Float, sc: Scene) {
        val ctx = RuntimeEnvironment.getApplication()
        val d = ctx.resources.displayMetrics.density
        val frame = WidgetUpdater.Frame(sc.readings, now, sc.treatments)
        val latest = sc.readings.lastOrNull()
        when (s) {
            CardSurface.WIDGET_VALUE, CardSurface.WIDGET_GRAPH, CardSurface.WIDGET_COMBINED -> {
                val kind = when (s) { CardSurface.WIDGET_VALUE -> WidgetKind.VALUE; CardSurface.WIDGET_GRAPH -> WidgetKind.GRAPH; else -> WidgetKind.COMBINED }
                app.mauri.sukrara.widgets.renderWidget(ctx, kind, w, h, frame, WidgetGraphConfig(), metricsOf(sc)).recycle()
            }
            CardSurface.APP_PANEL -> {
                val pal = systemPalette(ctx, false)
                panel = {
                    Box(Modifier.width(w.dp)) {
                        app.mauri.sukrara.ui.PanelCard(latest, sc.readings.dropLast(1), sc.treatments, now, GraphSettings.value, pal,
                            AlertSettings(), emptyMap(), sc.sensor, SensorLife.countdown.value, metricsOf(sc), onEnded = null)
                    }
                }
                compose.waitForIdle()
                compose.onRoot().captureToImage()
            }
            CardSurface.FIRST_READING -> latest?.let {
                val bmp = createBitmap((w * d).toInt(), (h * d).toInt())
                app.mauri.sukrara.ui.drawFirstReading(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), it, now, systemPalette(ctx, false), d)
                bmp.recycle()
            }
            CardSurface.LOCKSCREEN -> {
                val bmp = createBitmap((w * d).toInt(), (h * d).toInt())
                app.mauri.sukrara.widgets.draw(ctx, Canvas(bmp), frame, app.mauri.sukrara.widgets.Hole(w / 2 - 44, w / 2 + 44, 76f), d,
                    FeatureState(), metricsOf(sc))
                bmp.recycle()
            }
            CardSurface.NOTIFICATION -> {
                // build() draws the card's parts only while a value shows (chip off); the same condition here
                val look = SensorBadge.look(sc.sensor, now, latest?.t)
                if (latest != null && GlucoseCard.face(latest, now, look, CardSurface.TEST).shown)
                    app.mauri.sukrara.status.GlucoseNotification.paintArt(ctx, false, 0xFFEEEEEE.toInt(), sc.readings, sc.treatments, now, w, latest, metricsOf(sc))
            }
            CardSurface.NOTIFICATION_TEXT ->
                app.mauri.sukrara.status.GlucoseNotification.build(ctx, latest, chip = false, icon = true, detail = "", now = now)
            CardSurface.STATUS_BAR -> latest?.let { app.mauri.sukrara.hook.chipFace(it, now, sc.sensor) }
            CardSurface.TEST -> error("not a surface")
        }
    }

    // ---- what a render shows -----------------------------------------------------------------------------------

    private fun strip(info: String) = info.replace(Regex("\\s*\\[[^]]*]"), "").replace(Regex("\\s*\\(no room\\)"), "").trim()

    private fun faceElements(f: ValueFace): List<Pair<String, String>> = when (f.state) {
        ValueFace.State.VALUE -> listOfNotNull("value.number" to (f.number + if (f.strike) " struck" else ""),
            if (f.arrow) "value.arrow" to (if (app.mauri.sukrara.core.Trend.isDouble(f.rate!!)) "double" else "single") else null)
        ValueFace.State.WARMING_UP -> listOf("value.warmup" to (f.warmup ?: ""))
        ValueFace.State.ENDED -> listOf("value.ended" to "")
        else -> listOf("value.none" to f.state.name)
    }

    private fun facts(r: Render): Map<String, String> {
        val st = r.layouts.firstOrNull()?.style
        val chart = r.layouts.firstNotNullOfOrNull { l -> l.first("chart")?.box?.let { l to it } }
        val slots = r.layouts.firstOrNull { it.first("slots") != null }
        // the box the slots were laid out in (the value's row, or a box of their own)
        val slotsBox = slots?.first("slots.box")?.box
        fun wh(w: Float, h: Float, d: Float) = "${Math.round(w / d)}x${Math.round(h / d)}"
        return mapOf(
            "scene" to r.scene, "settings" to r.settings,
            // a card that shows the value, or a glyph surface (it shows nothing else)
            "face" to (if (st == null) "${r.faces.isNotEmpty()}" else "${st.value}"),
            "value" to (if (st == null) "glyph" else "${st.value}"),
            "graph" to "${st?.graph != null}",
            "labels" to "${st?.graph?.axisLabels ?: "-"}", "side" to "${st?.graph?.side ?: "-"}",
            "hours" to "${st?.graph?.windowS?.div(3600) ?: "-"}",
            "sensorTime" to "${st?.sensorTime ?: "-"}", "badge" to "${st?.badge ?: "-"}",
            "rings" to (st?.let { ringsSignature(it) } ?: "-"),
            "chart" to (chart?.let { (l, b) -> wh(b.width(), b.height(), l.d) } ?: "-"),
            "pctWide" to (chart?.let { (l, b) -> if (b.width() / l.d >= GlucoseGraph.PCT_MIN_WIDTH_DP) "1" else "0" } ?: "-"),
            "lane2" to "${r.layouts.any { it.first("chart.lane2") != null }}",
            "sensorShown" to "${r.layouts.any { l -> l.all("chart.sensor").any { !it.info.contains("(no room)") } }}",
            "part" to (slots?.part?.name?.lowercase() ?: "-"),
            "box" to (slots?.let { l -> slotsBox?.let { wh(it.width(), it.height(), l.d) } } ?: "-"),
        )
    }

    /** The slots a style asks for, in words both platforms write the same: kind:window:range, the last-treatment place and its fallback. */
    private fun ringsSignature(st: CardStyle) =
        st.rings.joinToString(",") { "${it.kind.name}:${it.windowS}:${it.range.name}:${if (it.marks) 1 else 0}" } +
            "|${st.last.name}|" + (st.lastElse?.let { "${it.kind.name}:${it.windowS}" } ?: "-")

    private fun descriptor(r: Render, rule: JSONObject): List<String> {
        val names = rule.getJSONArray("elements").let { a -> (0 until a.length()).map(a::getString).toSet() }
        val box = rule.optBoolean("box")
        val chart = r.layouts.firstNotNullOfOrNull { it.first("chart")?.box }
        val els = r.layouts.flatMap { l -> l.elements.filter { it.name in names }.map { l to it } }
        if (rule.optString("mode") == "subset")
            return els.sortedBy { it.second.box?.left ?: 0f }.map { strip(it.second.info) }
        val out = els.map { (l, e) ->
            val b = e.box
            val geo = if (box && b != null && chart != null)
                " @${CardLayout.dp(b.left - chart.left, l.d)},${CardLayout.dp(chart.bottom - b.top, l.d)},${CardLayout.dp(b.width(), l.d)}x${CardLayout.dp(b.height(), l.d)}"
            else ""
            "${e.name} ${strip(e.info)}".trim() + geo
        } + r.faces.flatMap(::faceElements).filter { it.first in names }.map { "${it.first} ${it.second}".trim() }
        return out.distinct().sorted()
    }

    // ---- the test ----------------------------------------------------------------------------------------------

    @Test fun everySurfaceFollowsTheSameRules() {
        val ctx = RuntimeEnvironment.getApplication()
        RoundedFont.init(ctx)
        app.mauri.sukrara.core.Words.loader = { app.mauri.sukrara.core.Words.of(ctx.resources) }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        Clock.hour24Source = { true }
        val reg = JSONObject(File(dir(), "surfaces/surfaces.json").readText()).getJSONObject("android")
        val ids = (0 until reg.getJSONArray("surfaces").length()).map { reg.getJSONArray("surfaces").getJSONObject(it).getString("id") }
        assertTrue("CardSurface and surfaces.json differ: ${CardSurface.entries.map { it.id }} vs $ids",
            CardSurface.entries.filter { it != CardSurface.TEST }.map { it.id }.sorted() == ids.sorted())

        compose.setContent { panel?.invoke() }
        GlucoseCard.observer = { synchronized(captured) { captured += it } }
        GlucoseCard.faceObserver = { s, f -> if (s != CardSurface.TEST) synchronized(faces) { faces += f } }
        val renders = ArrayList<Render>()
        val base = GraphSettings.value
        try {
            for ((sid, apply) in settings()) {
                GraphSettings.update { apply(base) }
                for (sc in scenes()) {
                    SensorLife.update(ctx, sc.sensor)
                    for (s in CardSurface.entries) {
                        if (s == CardSurface.TEST) continue
                        // glyphs don't read the graph settings: once is enough
                        if (s.kind == CardSurface.Kind.GLYPH && sid != "default") continue
                        for ((w, h) in sizes(reg, s)) {
                            synchronized(captured) { captured.clear() }
                            synchronized(faces) { faces.clear() }
                            render(s, w, h, sc)
                            val ls = synchronized(captured) { captured.filter { it.surface == s } }
                                // a composable may draw a part more than once: its last drawing counts
                                .groupBy { it.part }.map { it.value.last() }
                            val fs = if (s.kind == CardSurface.Kind.GLYPH) synchronized(faces) { faces.toList().distinct() } else emptyList()
                            renders += Render(s, "${w.toInt()}x${h.toInt()}", sc.id, sid, ls, fs)
                        }
                    }
                }
            }
        } finally {
            GraphSettings.update { base }
            SensorLife.update(ctx, null)
            GlucoseCard.observer = null
            GlucoseCard.faceObserver = null
        }

        val problems = ArrayList<String>()
        // each card surface draws exactly the parts it is registered with
        for (s in CardSurface.entries.filter { it != CardSurface.TEST && it.kind == CardSurface.Kind.CARD }) {
            val e = (0 until reg.getJSONArray("surfaces").length()).map { reg.getJSONArray("surfaces").getJSONObject(it) }.first { it.getString("id") == s.id }
            val want = e.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val drawn = renders.filter { it.surface == s }.flatMap { r -> r.layouts.map { it.part.name.lowercase() } }.toSet()
            if (drawn != want) problems += "${s.id}: draws the parts $drawn, registered $want"
        }
        for (s in CardSurface.entries.filter { it.kind == CardSurface.Kind.GLYPH })
            if (renders.none { it.surface == s && it.faces.isNotEmpty() }) problems += "${s.id}: its entry path never asked GlucoseCard.face"

        // the rules: same inputs → same elements, whatever the surface
        val rules = JSONObject(File(dir(), "layout/rules.json").readText()).getJSONArray("rules")
        val answers = sortedMapOf<String, String>()
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val by = rule.getJSONArray("by").let { a -> (0 until a.length()).map(a::getString) }
            val subset = rule.optString("mode") == "subset"
            val groups = renders.filter { r -> r.layouts.isNotEmpty() || r.faces.isNotEmpty() }
                .groupBy { r -> facts(r).let { f -> by.joinToString(";") { "$it=${f[it]}" } } }
            for ((key, rs) in groups) {
                val ds = rs.map { it to descriptor(it, rule) }
                if (subset) {
                    val longest = ds.maxBy { it.second.size }.second
                    for ((r, d) in ds) if (!isSubsequence(d, longest))
                        problems += "${rule.getString("name")} [$key]: $r shows $d, not a cut of $longest (${ds.first { it.second == longest }.first})"
                    if (rule.optBoolean("shared")) answers["${rule.getString("name")}|$key"] = longest.joinToString(" | ")
                } else {
                    val distinct = ds.groupBy({ it.second }, { it.first })
                    if (distinct.size > 1) problems += "${rule.getString("name")} [$key] differs between surfaces:\n" +
                        distinct.entries.joinToString("\n") { (d, who) -> "      $d  ← ${who.distinct().take(6).joinToString()}${if (who.size > 6) " …" else ""}" }
                    if (rule.optBoolean("shared")) answers["${rule.getString("name")}|$key"] = ds.first().second.joinToString(" | ")
                }
            }
        }
        if (problems.isNotEmpty()) fail("${problems.size} surface rule failures (spikes/graph-fixtures/layout/rules.json):\n  " +
            problems.take(40).joinToString("\n  "))

        // the shared answers: what the Mac checks its surfaces against
        val file = File(dir(), "layout/expect.json")
        if (System.getProperty("layout.write") == "true") {
            file.writeText(JSONObject().apply {
                put("about", "Generated by CardLayoutTest (-Dlayout.write=true): for each shared rule of rules.json and each combination of its inputs that the Android surfaces render, what every surface shows. The Mac's -graphselftest checks its surfaces against it; Android checks it is still exactly this. Review changes like code: this is where Android and the Mac agree.")
                put("answers", JSONObject().apply { answers.forEach { (k, v) -> put(k, v) } })
            }.toString(1) + "\n")
            return
        }
        val expect = JSONObject(file.readText()).getJSONObject("answers")
        val diffs = ArrayList<String>()
        for ((k, v) in answers) if (!expect.has(k)) diffs += "new: $k → $v" else if (expect.getString(k) != v) diffs += "changed: $k\n      was ${expect.getString(k)}\n      now $v"
        for (k in expect.keys()) if (k !in answers) diffs += "gone: $k → ${expect.getString(k)}"
        if (diffs.isNotEmpty()) fail("layout/expect.json is out of date (${diffs.size}); if the change is intended, run with -Dlayout.write=true, " +
            "review the diff and make the Mac follow (-graphselftest):\n  " + diffs.take(30).joinToString("\n  "))
    }

    private fun isSubsequence(a: List<String>, b: List<String>): Boolean {
        var j = 0
        for (x in a) { while (j < b.size && b[j] != x) j++; if (j == b.size) return false; j++ }
        return true
    }
}
