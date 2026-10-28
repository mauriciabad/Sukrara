// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Bitmap
import android.graphics.Canvas
import app.mauri.sukrara.core.model.CardStatus
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.graph.Fixtures.bool
import app.mauri.sukrara.graph.Fixtures.double
import app.mauri.sukrara.graph.Fixtures.long
import app.mauri.sukrara.graph.Fixtures.str
import app.mauri.sukrara.graph.stats.GlucosePoint
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * The layout rules of contracts/graph/layout (rules.json over scenes.json; expect.json = the answers the Mac checks
 * too), run on the renderer itself. Each registered card surface is
 * stood in for by its shape (parts and sizes from surfaces.json, its place's settings): every rule must hold across
 * all of them, and every shared answer they produce must equal expect.json (this test never rewrites it). Once the
 * surfaces exist, their real entry paths replace the stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CardLayoutTest {
    private val savedZone = TimeZone.getDefault()
    private val savedLocale = Locale.getDefault()
    private val d = 1f

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        RoundedFont.init(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        Clock.hour24Source = null
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    private val sceneFile by lazy { Fixtures.read("layout/scenes.json") }
    private val rulesFile by lazy { Fixtures.read("layout/rules.json") }
    private val now by lazy { sceneFile["now"].long()!! }

    private class Scene(
        val id: String,
        val readings: List<GlucosePoint>,
        val treatments: List<Treatment>,
        val sensor: SensorInfo?,
    )

    /** One stand-in surface: the parts it draws (each with its height; 0 = the size's), and its sizes. */
    private class Shape(
        val id: String,
        val place: GraphPlace,
        val parts: List<Pair<CardPart, Float>>,
        val sizes: List<Pair<Float, Float>>,
        val style: (GraphConfig) -> CardStyle,
    )

    private class Render(
        val shape: String,
        val size: String,
        val scene: String,
        val settings: String,
        val layouts: List<CardLayout>,
    ) {
        override fun toString() = "$shape $size"
    }

    /** Readings every 5 min from -days to -age, a triangle wave lo..hi with the period, then the `last` reading. */
    private fun scenes(): List<Scene> =
        sceneFile["scenes"]!!.jsonArray.map { it.jsonObject }.map { o ->
            val days = o["days"].long() ?: 0
            val readings =
                if (days == 0L) {
                    emptyList()
                } else {
                    val lo = o["lo"].long()!!.toInt()
                    val hi = o["hi"].long()!!.toInt()
                    val period = o["period"].long()!!
                    val half = period / 2
                    val first = now - days * 86_400
                    val last = now - o["age"].long()!!
                    val out = ArrayList<GlucosePoint>()
                    var t = first
                    while (t < last) {
                        val k = Math.floorMod(t - first, period)
                        val tri = if (k <= half) k else period - k
                        out += GlucosePoint(t, (lo + (hi - lo) * tri / half).toInt(), null)
                        t += 300
                    }
                    out += GlucosePoint(last, o["last"].long()!!.toInt(), o["rate"].double())
                    out
                }
            val trs =
                o["treatments"]?.jsonArray.orEmpty().map { it.jsonObject }.map {
                    val at = Instant.ofEpochSecond(now + it["t"].long()!!)
                    Treatment(
                        it["id"].str()!!,
                        TreatmentKind.entries.first { k -> k.id == it["k"].str() },
                        it["a"].double()!!,
                        at,
                        note = it["n"].str().orEmpty(),
                        modifiedAt = at,
                        origin = TreatmentOrigin.Phone,
                    )
                }
            val sensor =
                o["sensor"]?.jsonObject?.let {
                    SensorInfo(
                        now + it["start"].long()!!,
                        now + it["end"].long()!!,
                        now + it["warmupEnd"].long()!!,
                    )
                }
            Scene(o["id"].str()!!, readings, trs, sensor)
        }

    /** scenes.json `settings`: id, the GraphConfig it runs with and the phone's 24-hour setting. */
    private fun settings(): List<Triple<String, GraphConfig, Boolean>> =
        sceneFile["settings"]!!.jsonArray.map { it.jsonObject }.map { o ->
            var x = GraphConfig()
            if (o["sensorTime"].bool() == false) x = x.copy(sensorHidden = GraphPlace.entries.toSet())
            o["side"].bool()?.let { x = x.copy(side = it) }
            o["ringCount"].long()?.let { x = x.copy(ringCount = it.toInt()) }
            o["lastPlace"].str()?.let { x = x.copy(lastPlace = LastPlace.valueOf(it)) }
            o["lines"]?.jsonArray?.let { l -> x = x.copy(lines = l.map { it.long()!!.toInt() }) }
            o["gridHours"].long()?.let { x = x.copy(gridHours = it.toInt()) }
            o["high"].long()?.let { x = x.copy(bands = x.bands.copy(high = it.toInt())) }
            when (val m = o["marks"]) {
                null -> {}

                is JsonPrimitive -> {
                    x = x.copy(marks = MarkShow.ALL)
                }

                is JsonObject -> {
                    x =
                        x.copy(
                            marks =
                                MarkShow.parse(
                                    m.entries.joinToString(",") { (k, v) ->
                                        "$k:" +
                                            v.jsonArray.joinToString("+") { it.str()!! }
                                    },
                                ),
                        )
                }

                else -> {
                    error("marks: $m")
                }
            }
            Triple(o["id"].str()!!, x, o["hour24"].bool() ?: true)
        }

    private fun sizes(id: String): List<Pair<Float, Float>> {
        val reg =
            Fixtures
                .read("surfaces/surfaces.json")["android"]!!
                .jsonObject["surfaces"]!!
                .jsonArray
                .map { it.jsonObject }
        return reg.first { it["id"].str() == id }["sizes"]!!.jsonArray.map { s ->
            s.jsonArray.let {
                it[0].double()!!.toFloat() to
                    it[1].double()!!.toFloat()
            }
        }
    }

    private fun full(
        c: GraphConfig,
        p: GraphPlace,
        graph: Boolean = true,
    ) = CardStyle(
        graph = if (graph) c.graphStyle(c.hours(p)) else null,
        rings = c.rings(),
        last = c.lastPlace,
        lastElse = c.lastElseRing(),
        sensorTime = c.sensorTime(p),
        // a card without a chart still colours the value and the rings by the user's bands
        bands = c.bands,
    )

    /** The registered card surfaces' shapes (surfaces.json parts and sizes). */
    private fun shapes() =
        listOf(
            // the widgets' real style (WidgetStyle.kt, what :widgets draws with)
            Shape("widget-value", GraphPlace.WIDGETS, listOf(CardPart.ALL to 0f), sizes("widget-value")) {
                widgetCardStyle(CardSurface.WIDGET_VALUE, it)
            },
            Shape("widget-graph", GraphPlace.WIDGETS, listOf(CardPart.ALL to 0f), sizes("widget-graph")) {
                widgetCardStyle(CardSurface.WIDGET_GRAPH, it)
            },
            Shape("widget-combined", GraphPlace.WIDGETS, listOf(CardPart.ALL to 0f), sizes("widget-combined")) {
                widgetCardStyle(CardSurface.WIDGET_COMBINED, it)
            },
            Shape("app-panel", GraphPlace.APP, listOf(CardPart.HEADER to 120f, CardPart.GRAPH to 180f), sizes("app-panel")) {
                full(it, GraphPlace.APP)
            },
            // Layout coverage only, not registered surfaces (not in the app; surfaces.json "about"): the shapes the Mac
            // shares answers with in expect.json (a wide 3-ring lock-screen card, a value-only card, the notification's
            // header + graph). Drop one only together with the expect.json answers it alone produces.
            Shape("lockscreen-shape", GraphPlace.WIDGETS, listOf(CardPart.ALL to 0f), listOf(380f to 200f, 360f to 170f)) {
                full(it, GraphPlace.WIDGETS)
            },
            Shape("value-only-shape", GraphPlace.APP, listOf(CardPart.VALUE to 0f), listOf(220f to 72f)) {
                full(it, GraphPlace.APP, graph = false)
            },
            Shape("notification-shape", GraphPlace.NOTIFICATION, listOf(CardPart.HEADER to 64f, CardPart.GRAPH to 96f), listOf(291f to 0f, 240f to 0f)) {
                full(it, GraphPlace.NOTIFICATION)
            },
        )

    /** The alert settings' default mutes after insulin and carbs (the schema's defaults). */
    private val defaultMutes =
        MuteRule { t ->
            when (t.kind) {
                TreatmentKind.RapidInsulin -> SettingsSpec.MUTE_INSULIN.default
                TreatmentKind.Carbs -> SettingsSpec.MUTE_CARBS.default
                else -> null
            }?.let { t.at.epochSecond + (it * 60).toLong() }
        }

    /**
     * What the alert runtime says for a scene with the default thresholds, reduced to what these scenes
     * need: a current reading past the low / high threshold needs action; nothing covers or mutes it here.
     */
    private fun standInStatus(sc: Scene): CardStatus {
        val r = sc.readings.lastOrNull() ?: return CardStatus.None
        if (!Freshness.of(now - r.t).showGraph) return CardStatus.None
        return when {
            r.sgv < SettingsSpec.THRESHOLD_LOW.default -> CardStatus.Needed(lowSide = true)
            r.sgv > SettingsSpec.THRESHOLD_HIGH.default -> CardStatus.Needed(lowSide = false)
            else -> CardStatus.None
        }
    }

    private fun renderAll(): List<Render> {
        val words = Scenes.words()
        val pal = GraphPalette.of(false)
        val out = ArrayList<Render>()
        val scenes = scenes()
        for ((sid, cfg, h24) in settings()) {
            Clock.hour24Source = { h24 }
            for (sc in scenes) {
                val model =
                    CardModel(
                        readings = sc.readings,
                        treatments = sc.treatments,
                        nowS = now,
                        words = words,
                        status = standInStatus(sc),
                        muteRule = defaultMutes,
                        sensor = sc.sensor,
                    )
                for (shape in shapes()) {
                    val st = shape.style(cfg)
                    for ((w, h) in shape.sizes) {
                        val layouts =
                            shape.parts.map { (part, ph) ->
                                val bh = if (ph > 0) ph else h
                                val bmp = Bitmap.createBitmap((w * d).toInt(), (bh * d).toInt(), Bitmap.Config.ARGB_8888)
                                GlucoseCard
                                    .draw(
                                        Canvas(bmp),
                                        w * d,
                                        bh * d,
                                        model,
                                        st,
                                        pal,
                                        d,
                                        CardSurface.TEST,
                                        part,
                                    ).also { bmp.recycle() }
                            }
                        out += Render(shape.id, "${w.toInt()}x${h.toInt()}", sc.id, sid, layouts)
                    }
                }
            }
        }
        return out
    }

    private fun strip(info: String) = info.replace(Regex("\\s*\\[[^]]*]"), "").replace(Regex("\\s*\\(no room\\)"), "").trim()

    private fun wh(
        w: Float,
        h: Float,
    ) = "${Math.round(w / d)}x${Math.round(h / d)}"

    /** The inputs a rule's `by` may name, for one render (the same facts as the Mac's -graphselftest). */
    private fun facts(r: Render): Map<String, String> {
        val st = r.layouts.first().style
        val chart = r.layouts.firstNotNullOfOrNull { l -> l.first("chart")?.box }
        val slots = r.layouts.firstOrNull { it.first("slots") != null }
        val slotsBox = slots?.first("slots.box")?.box
        return mapOf(
            "scene" to r.scene,
            "settings" to r.settings,
            "face" to "${st.value || (st.graph != null && chart == null && r.layouts.any { it.part == CardPart.ALL })}",
            "value" to "${st.value}",
            "graph" to "${st.graph != null}",
            "labels" to "${st.graph?.axisLabels ?: "-"}",
            "side" to "${st.graph?.side ?: "-"}",
            "hours" to "${st.graph?.windowS?.div(3600) ?: "-"}",
            "sensorTime" to "${st.sensorTime}",
            "badge" to "${st.badge}",
            "rings" to ringsSignature(st),
            "chart" to (chart?.let { wh(it.width(), it.height()) } ?: "-"),
            "pctWide" to (chart?.let { if (it.width() / d >= GlucoseGraph.PCT_MIN_WIDTH_DP) "1" else "0" } ?: "-"),
            "lane2" to "${r.layouts.any { it.first("chart.lane2") != null }}",
            "sensorShown" to "${r.layouts.any { l -> l.all("chart.sensor").any { !it.info.contains("(no room)") } }}",
            "part" to (slots?.part?.name?.lowercase() ?: "-"),
            "box" to (slotsBox?.let { wh(it.width(), it.height()) } ?: "-"),
        )
    }

    private fun ringsSignature(st: CardStyle) =
        st.rings.joinToString(",") { "${it.kind.name}:${it.windowS}:${it.range.name}:${if (it.marks) 1 else 0}" } +
            "|${st.last.name}|" + (st.lastElse?.let { "${it.kind.name}:${it.windowS}" } ?: "-")

    private fun descriptor(
        r: Render,
        rule: JsonObject,
    ): List<String> {
        val names = rule["elements"]!!.jsonArray.map { it.str()!! }.toSet()
        val box = rule["box"].bool() == true
        val chart = r.layouts.firstNotNullOfOrNull { it.first("chart")?.box }
        val els = r.layouts.flatMap { l -> l.elements.filter { it.name in names }.map { l to it } }
        if (rule["mode"].str() == "subset") return els.sortedBy { it.second.box?.left ?: 0f }.map { strip(it.second.info) }
        return els
            .map { (l, e) ->
                val b = e.box
                val geo =
                    if (box && b != null && chart != null) {
                        " @${CardLayout.dp(b.left - chart.left, l.d)},${CardLayout.dp(chart.bottom - b.top, l.d)}," +
                            "${CardLayout.dp(b.width(), l.d)}x${CardLayout.dp(b.height(), l.d)}"
                    } else {
                        ""
                    }
                "${e.name} ${strip(e.info)}".trim() + geo
            }.distinct()
            .sorted()
    }

    private fun isSubsequence(
        a: List<String>,
        b: List<String>,
    ): Boolean {
        var i = 0
        for (x in b) if (i < a.size && a[i] == x) i++
        return i == a.size
    }

    @Test
    fun everySurfaceShapeFollowsTheSameRules() {
        val renders = renderAll()
        val problems = ArrayList<String>()

        // the rules: same inputs → same elements, whatever the surface; shared rules give the answers the Mac checks
        val answers = sortedMapOf<String, String>()
        for (rule in rulesFile["rules"]!!.jsonArray.map { it.jsonObject }) {
            val name = rule["name"].str()!!
            val by = rule["by"]!!.jsonArray.map { it.str()!! }
            val groups = renders.groupBy { r -> facts(r).let { f -> by.joinToString(";") { "$it=${f[it]}" } } }
            for ((key, rs) in groups) {
                val ds = rs.map { it to descriptor(it, rule) }
                val answer =
                    if (rule["mode"].str() == "subset") {
                        val longest = ds.maxBy { it.second.size }.second
                        for ((r, dd) in ds) if (!isSubsequence(dd, longest)) problems += "$name [$key]: $r shows $dd, not a cut of $longest"
                        longest
                    } else {
                        val distinct = ds.groupBy({ it.second }, { it.first })
                        if (distinct.size > 1) {
                            problems += "$name [$key] differs between surfaces:\n" +
                                distinct.entries.joinToString("\n") { (dd, who) -> "      $dd  ← ${who.distinct().take(4).joinToString()}" }
                        }
                        ds.first().second
                    }
                if (rule["shared"].bool() == true) answers["$name|$key"] = answer.joinToString(" | ")
            }
        }

        // the gaps: a column is its widest drawn label plus one constant margin on each side
        for (g in rulesFile["gaps"]!!.jsonArray.map { it.jsonObject }) {
            val gap = g["gap"].double()!!
            val column = g["column"].str()!!
            var seen = 0
            for (r in renders) {
                for (l in r.layouts) {
                    val col = l.first(column)?.box ?: continue
                    val ls = l.all(g["labels"].str()!!).mapNotNull { e -> e.box?.let { e.info to it } }
                    if (ls.isEmpty()) {
                        problems += "$column: $r draws no labels"
                        continue
                    }
                    seen++
                    val alignRight = g["align"].str() == "right"
                    val each = ls.map { (_, b) -> (if (alignRight) col.right - b.right else b.left - col.left) / l.d }
                    val widest =
                        (if (alignRight) ls.minOf { it.second.left } - col.left else col.right - ls.maxOf { it.second.right }) / l.d
                    if (abs(widest - gap) > 0.05 || each.any { abs(it - gap) > 0.05 }) {
                        problems += "$column gaps on $r (${r.scene}/${r.settings}): widest $widest, aligned $each; want $gap"
                    }
                }
            }
            if (seen == 0) problems += "$column: no render shows it (gaps rule untested)"
        }

        // the sensor badge never moves a label: with and without it, the same layout
        val badge = rulesFile["badge"]!!.jsonObject
        val (withBadge, noBadge) = badge["compare"]!!.jsonArray.map { it.str()!! }
        val badgeEls = badge["elements"]!!.jsonArray.map { it.str()!! }.toSet()

        fun badgeDesc(r: Render) =
            r.layouts
                .flatMap { l ->
                    l.elements.filter { it.name in badgeEls }.map { e ->
                        "${e.name} ${e.info}" +
                            (
                                e.box?.let { b ->
                                    " @${CardLayout.dp(
                                        b.left,
                                        l.d,
                                    )},${CardLayout.dp(b.top, l.d)},${CardLayout.dp(b.right, l.d)},${CardLayout.dp(b.bottom, l.d)}"
                                }
                                    ?: ""
                            )
                    }
                }.sorted()
        var compared = 0
        for (r in renders.filter {
            it.settings == withBadge &&
                it.layouts.any { l -> l.all("chart.sensor").any { e -> !e.info.contains("(no room)") } }
        }) {
            val o =
                renders.firstOrNull { it.shape == r.shape && it.size == r.size && it.scene == r.scene && it.settings == noBadge }
                    ?: continue
            compared++
            val a = badgeDesc(r)
            val b = badgeDesc(o)
            if (a !=
                b
            ) {
                problems +=
                    "badge on $r (${r.scene}): the layout changes with the sensor badge: with ${a - b.toSet()} without ${b - a.toSet()}"
            }
        }
        if (compared == 0) problems += "badge: no render shows the sensor badge (rule untested)"

        // the distribution's erased outline: the dots plus one constant margin
        val dist = rulesFile["distribution"]!!.jsonObject
        if (abs(
                GlucoseGraph.PROFILE_PITCH - dist["pitch"].double()!!,
            ) > 1e-4 || abs(GlucoseGraph.PROFILE_R - dist["dot"].double()!!) > 1e-4 ||
            abs(GlucoseGraph.PROFILE_CUT - dist["cut"].double()!!) > 1e-4
        ) {
            problems +=
                "distribution: GlucoseGraph ${GlucoseGraph.PROFILE_PITCH}/${GlucoseGraph.PROFILE_R}/${GlucoseGraph.PROFILE_CUT}, rules.json $dist"
        }
        var cuts = 0
        for (r in renders) {
            for (l in r.layouts) {
                val cut = l.first("chart.distribution.cut")?.box ?: continue
                val dots = l.first("chart.distribution.dots")?.box ?: continue
                cuts++
                val right = (cut.right - dots.right) / l.d
                val top = (dots.top - cut.top) / l.d
                val want = dist["cut"].double()!!
                if (abs(right - want) > 0.05 ||
                    abs(top - want) > 0.05
                ) {
                    problems += "distribution cut on $r: right $right top $top, want $want"
                }
            }
        }
        if (cuts == 0) problems += "distribution: no render erases its outline (rule untested)"

        // treatment labels only up to maxHours
        val maxHours = rulesFile["treatmentLabels"]!!.jsonObject["maxHours"].long()!!
        assertTrue("treatmentLabels maxHours", GlucoseGraph.LABELS_MAX_S == maxHours * 3600)

        if (problems.isNotEmpty()) {
            fail("${problems.size} layout rule failures (contracts/graph/layout/rules.json):\n  " + problems.take(40).joinToString("\n  "))
        }

        // the shared answers: every one these shapes produce is the one the Mac checks against
        val expect = Fixtures.read("layout/expect.json")["answers"]!!.jsonObject
        val diffs =
            answers
                .filter { (k, v) ->
                    expect[k]?.str()?.let { it != v } == true
                }.map { (k, v) ->
                    "changed: $k\n      want ${expect[k].str()}\n      got  $v"
                }
        val matched = answers.keys.count { it in expect }
        assertTrue("no shared answer matched expect.json (the stand-ins no longer cover its inputs)", matched > 0)
        if (diffs.isNotEmpty()) {
            fail("${diffs.size} of $matched shared answers differ from layout/expect.json:\n  " + diffs.take(30).joinToString("\n  "))
        }
        println("layout: ${renders.size} renders, $matched shared answers equal expect.json, ${answers.size - matched} not in it")
    }
}
