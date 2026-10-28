// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.graph.Fixtures.bool
import app.mauri.sukrara.graph.Fixtures.double
import app.mauri.sukrara.graph.Fixtures.long
import app.mauri.sukrara.graph.Fixtures.str
import app.mauri.sukrara.graph.stats.Goal
import app.mauri.sukrara.graph.stats.ShareText
import app.mauri.sukrara.graph.stats.TimeInRange
import app.mauri.sukrara.graph.stats.TirRange
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/** The renderer's decisions against contracts/graph (share, mark, sensor, place, labels, status); the Mac runs the same files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GraphFixtureTest {
    private fun cases(path: String) = Fixtures.read(path)["cases"]!!.jsonArray.map { it.jsonObject }

    private fun name(c: JsonObject) = c["name"].str()!!

    @Test
    fun share() {
        for (c in cases("share/share.json")) {
            val goal =
                when (c["goal"].str()) {
                    "veryHigh" -> Goal.VERY_HIGH
                    "above" -> Goal.ABOVE
                    "inRange" -> Goal.IN_RANGE
                    "below" -> Goal.BELOW
                    "veryLow" -> Goal.VERY_LOW
                    else -> null
                }
            val locale = Locale.forLanguageTag(c["locale"].str()!!)
            val x = c["x"].double()!!
            val got = if (c["goal"].str() == "coverage") ShareText.coverage(x, locale) else ShareText.of(x, goal, locale)
            assertEquals(name(c), c["expect"].str(), got)
        }
    }

    @Test
    fun mark() {
        for (c in cases("mark/mark.json")) {
            val t = c["tir"]!!.jsonObject

            fun v(k: String) = t[k].double() ?: 0.0
            val tir = TimeInRange(v("veryLow"), v("low"), v("tight"), v("target"), v("high"), v("veryHigh"), v("coverage"))
            val range = if (c["range"].str() == "tight") TirRange.TIGHT else TirRange.IN_RANGE
            val e = c["expect"]!!.jsonObject
            val spec = RingSpec(86_400, range)
            assertEquals(name(c), e["mark"].str(), TirMark.of(tir, range).name.lowercase())
            assertEquals(name(c), e["text"].str(), TirRing.text(tir, spec, Locale.US))
            val (lo, inr, hi) = GlucoseGraph.pct3(tir.below, tir.inRange, tir.above)
            assertEquals(name(c), e["column"]!!.jsonArray.map { it.long()!!.toInt() }, listOf(lo, inr, hi))
            assertEquals(name(c), e["crown"].toString() == "true", TirMark.column(tir))
            assertEquals(name(c), e["flower"].str(), Bloom.of(tir, range)?.name?.lowercase() ?: "none")
        }
    }

    @Test
    fun sensor() {
        val w = Scenes.words()
        for (c in cases("sensor/life.json")) {
            val now = Scenes.NOW
            val start = now + c["start"].long()!!
            val info = SensorInfo(start, now + c["end"].long()!!, c["warmup"].long()?.let { now + it } ?: (start + 3600))
            val latest = c["latest"].long()?.let { now + it }
            val look = SensorBadge.look(info, now, latest, w)!!
            val e = c["expect"]!!.jsonObject
            assertEquals(name(c), e["label"].str(), look.label)
            assertEquals(name(c), e["ended"].toString() == "true", look.ended)
            assertEquals(name(c), e["life"].double()!!, look.life.toDouble(), 0.001)
            assertEquals(name(c), e["warmup"].str(), look.warmup)
            val ring = SensorBadge.ring(look, info, now, c["countdownHours"].long()!!.toInt())
            val want = e["ring"].double()
            if (want == null) assertNull(name(c), ring) else assertEquals(name(c), want, ring!!, 0.001)
            val pill = e["pill"]
            if (pill == null || pill is JsonNull) {
                assertEquals(name(c), false, look.ended && latest == null)
            } else {
                val p = StatusBadge.replaceSensor(w)
                assertEquals(name(c), pill.jsonObject["text"].str(), p.text)
                assertEquals(name(c), pill.jsonObject["short"].str(), p.short)
            }
        }
    }

    @Test
    fun place() {
        val file = Fixtures.read("place/place.json")
        val d = 1f
        for (r in file["rooms"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(r["label"].str(), r["room"].double()!!, GlucoseGraph.sensorWidth(r["label"].str()!!, d).toDouble(), 0.05)
        }
        val w = Scenes.words()
        for (c in cases("place/place.json")) {
            val card = c["card"]!!.jsonObject
            val graph = card["graph"].toString() == "true"
            val style =
                CardStyle(
                    graph = if (graph) GraphStyle(axisLabels = card["axisLabels"]?.toString() != "false") else null,
                    value = card["value"].toString() == "true",
                    sensorTime = card["sensorTime"]?.toString() != "false",
                )
            val now = Scenes.NOW
            val info =
                when (c["sensor"].str()) {
                    "live" -> SensorInfo(now - 2 * 86_400, now + 13 * 86_400)
                    "warmup" -> SensorInfo(now - 600, now + 15 * 86_400)
                    "ended" -> SensorInfo(now - 15 * 86_400, now - 60)
                    else -> null
                }
            val age = c["age"].long()!!
            val look = SensorBadge.look(info, now, if (c["sensor"].str() == "warmup") null else now - age, w)
            val spot = GlucoseCard.sensorSpot(style, look, Freshness.of(age))
            assertEquals(name(c), c["expect"]!!.jsonObject["spot"].str(), spot.name.lowercase())
            val box = c["expect"]!!.jsonObject["box"]?.jsonArray ?: continue
            val chart = c["chart"]!!.jsonObject
            val top = GlucoseGraph.sensorTop(chart["h"].double()!!.toFloat(), 0f, d)
            assertEquals(name(c), box[1].double()!!, top.toDouble(), 0.05)
            assertEquals(name(c), box[2].double()!!, GlucoseGraph.SENSOR_DP.toDouble(), 0.05)
        }
    }

    /** A fixture treatment: `t` in seconds from [now], `k` the kind's contract id. */
    private fun treatments(
        c: JsonObject,
        now: Long,
    ) = c["treatments"]!!.jsonArray.map { it.jsonObject }.map { o ->
        val at = Instant.ofEpochSecond(now + o["t"].long()!!)
        Treatment(
            id = o["id"].str()!!,
            kind = TreatmentKind.entries.first { it.id == o["k"].str() },
            amount = o["a"].double()!!,
            at = at,
            note = o["n"].str().orEmpty(),
            modifiedAt = at,
            origin = TreatmentOrigin.Phone,
        )
    }

    /** A case's `marks`: absent = the defaults, "all" = every part, or `{kind: [parts]}` (missing kinds = their default). */
    private fun marksOf(c: JsonObject): MarkShow =
        when (val m = c["marks"]) {
            null -> MarkShow.DEFAULT
            is JsonPrimitive -> if (m.content == "all") MarkShow.ALL else error("marks: $m")
            is JsonObject -> MarkShow.parse(m.entries.joinToString(",") { (k, v) -> "$k:" + v.jsonArray.joinToString("+") { it.str()!! } })
            else -> error("marks: $m")
        }

    /**
     * The treatment label layout (labels/labels.json): the plot's edges, the lanes, and which labels are drawn (members,
     * text, lane, centre in dp from the chart's left) or dropped. Same file in the Mac's `-graphselftest`.
     */
    @Test
    fun labels() {
        val file = Fixtures.read("labels/labels.json")
        val pctLabel = file["pctLabel"].double()!!.toFloat()
        val valueLabel = file["valueLabel"].double()!!.toFloat()
        val w = Scenes.words()
        val now = Scenes.NOW
        val all = cases("labels/labels.json")
        assertTrue("no label cases", all.isNotEmpty())
        val fails = ArrayList<String>()
        for (c in all) {
            val chart = c["chart"]!!.jsonObject
            val cw = chart["w"].double()!!.toFloat()
            val lanes = GlucoseGraph.lanes(chart["h"].double()!!.toFloat(), 1f)
            val windowS = (c["hours"].double()!! * 3600).toLong()
            val (left, right) = GlucoseGraph.plotX(0f, cw, c["pct"].bool() != false, true, 1f, pctLabel, valueLabel)
            val from = now - windowS
            val lay =
                TreatmentMarks.layout(
                    treatments(c, now).filter { it.t in from..now },
                    { t -> left + (t - from).toFloat() / windowS * (right - left) },
                    now,
                    0f,
                    cw,
                    lanes,
                    1f,
                    w,
                    marksOf(c),
                )
            val e = c["expect"]!!.jsonObject
            val plot = e["plot"]!!.jsonArray.map { it.double()!! }
            val want = e["labels"]!!.jsonArray.map { it.jsonObject }
            val got = lay.labels.sortedBy { it.left + it.w / 2 }
            val dropped = lay.dropped.map { g -> g.map { it.id } }
            val ok =
                abs(plot[0] - left) <= 0.5 && abs(plot[1] - right) <= 0.5 &&
                    e["lanes"].long()!!.toInt() == lanes &&
                    e["dropped"]!!.jsonArray.map { g -> g.jsonArray.map { it.str() } } == dropped &&
                    want.size == got.size &&
                    want.zip(got).all { (x, y) ->
                        x["ids"]!!.jsonArray.map { it.str() } == y.members.map { it.id } && x["text"].str() == y.text &&
                            x["lane"].long()!!.toInt() == y.lane && abs(x["x"].double()!! - (y.left + y.w / 2)) <= 0.5
                    }
            if (!ok) {
                val dump = got.joinToString { "${it.members.map { m -> m.id }} '${it.text}' lane ${it.lane} x ${it.left + it.w / 2}" }
                fails += "${name(c)}\n  want $e\n  got  plot [$left, $right] lanes $lanes labels [$dump] dropped $dropped"
            }
        }
        assertTrue("label fixtures failed:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    /** The user's mute after insulin / carbs as status.json gives it (`settings`), else the schema defaults. */
    private fun muteRule(c: JsonObject): MuteRule {
        val settings = c["settings"]?.jsonObject

        fun muteS(
            key: String,
            defaultMin: Double,
        ): Long? {
            val o = settings?.get(key)?.jsonObject
            if (o?.get("on").bool() == false) return null
            return ((o?.get("min").double() ?: defaultMin) * 60).toLong()
        }
        val insulinS = muteS("muteAfterInsulin", SettingsSpec.MUTE_INSULIN.default)
        val carbsS = muteS("muteAfterCarbs", SettingsSpec.MUTE_CARBS.default)
        return MuteRule { t ->
            when (t.kind) {
                TreatmentKind.RapidInsulin -> insulinS
                TreatmentKind.Carbs -> carbsS
                else -> null
            }?.let { t.t + it }
        }
    }

    /**
     * The badge, last-treatment slot and amounts (status.json). The alert status itself is decided by the alert
     * runtime (with its own scenarios); here the card gets the expected status and must draw it as the file says.
     */
    @Test
    fun status() {
        val now = Scenes.NOW
        val w = Scenes.words()
        val all = cases("status.json")
        assertTrue("no status cases", all.isNotEmpty())
        for (c in all) {
            val n = name(c)
            val e = c["expect"]!!.jsonObject
            val trs = treatments(c, now)

            fun end(v: JsonElement?) = if (v.str() == "never") CardStatus.NEVER else now + v.long()!!
            val parts = e["status"].str()!!.split(":")
            val lowSide = parts.getOrNull(1).orEmpty().let { it.contains("low") || it.startsWith("falling") }
            val status =
                when (parts[0]) {
                    "none" -> CardStatus.None
                    "needed" -> CardStatus.Needed(lowSide)
                    "taken" -> trs.first { it.id == parts[2] }.let { CardStatus.Taken(it.kind, it.t, end(e["until"])) }
                    "paused" -> CardStatus.Paused(lowSide, end(e["until"]))
                    "muted" -> CardStatus.Muted(end(e["until"]))
                    else -> error("$n: status ${parts[0]}")
                }
            val badge = e["badge"]
            val look = StatusBadge.look(status, now, w)
            if (badge is JsonObject) {
                val want = StatusBadge.Look(badge["kind"].str()!!, badge["text"].str()!!, badge["short"].str()!!, badge["tonal"].bool()!!)
                assertEquals("$n: badge", want, look)
            } else {
                assertNull("$n: badge", look)
            }
            val last = LastTreatment.of(trs, muteRule(c), status, now)
            val el = e["last"]
            if (el is JsonObject) {
                assertEquals("$n: last id", el["id"].str(), last?.tr?.id)
                assertEquals("$n: last until", el["until"].long()?.let { now + it }, last?.until)
                val with = el["with"]?.jsonObject
                assertEquals("$n: last with", with?.get("id").str(), last?.carbs?.tr?.id)
                if (with != null) assertEquals("$n: last with until", now + with["until"].long()!!, last?.carbs?.until)
            } else {
                assertNull("$n: last", last)
            }
            e["amounts"]?.jsonObject?.forEach { (id, want) ->
                assertEquals("$n: amount $id", want.str(), TreatmentMarks.amount(trs.first { it.id == id }))
            }
        }
    }
}
