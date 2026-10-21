// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.settings

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.RingSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Android side of THE settings schema (spikes/settings/settings.json → SettingsSpec, round f28; the Mac runs the
 * same checks in `-settingsselftest`): every stored setting has a schema row and every Android row is stored; the
 * models' defaults are the schema's; every value the schema calls valid survives the settings file (custom values,
 * not only presets) and an invalid one comes back valid.
 */
class SettingsSchemaTest {
    private val rows = SettingsSpec.SETTINGS

    /** "alerts:alerts.{kind}.on" → every concrete key, per store. */
    private fun schemaKeys(platform: (SettingInfo) -> String): Map<String, Set<String>> = rows.mapNotNull { r ->
        platform(r).takeIf { it.isNotEmpty() }?.split(":", limit = 2)?.let { it[0] to it[1] }
    }.groupBy({ it.first }, { it.second }).mapValues { (_, ks) ->
        ks.flatMap { it.split(",") }.flatMap { k -> if ("{kind}" in k) Kind.ALL.map { k.replace("{kind}", it) } else listOf(k) }.toSet()
    }

    private fun flatten(o: JSONObject, prefix: String = ""): Set<String> = o.keys().asSequence().flatMap { k ->
        val v = o.get(k)
        if (v is JSONObject && v.length() > 0) flatten(v, "$prefix$k.").asSequence() else sequenceOf("$prefix$k")
    }.toSet()

    @Test fun everyStoredSettingIsInTheSchemaAndBack() {
        val android = schemaKeys { it.android }
        // alerts: the SPEC §3 JSON, with a sound override on every kind so "sounds.kinds.<kind>" is written too
        val alerts = flatten(AlertSettings(packFor = Kind.ALL.associateWith { "kart" }).toJson())
        val inSchema = android.getValue("alerts")
        // a kind's value key depends on the kind (mgdl / rate / min): the schema lists the right one per kind
        assertEquals("alert keys stored but not in the schema", emptySet<String>(), alerts - inSchema)
        assertEquals("alert keys in the schema but not stored", emptySet<String>(), inSchema - alerts)
        val graph = GraphConfig().toJson().keys().asSequence().toSet()
        assertEquals("graph keys stored but not in the schema", emptySet<String>(), graph - android.getValue("graph"))
        assertEquals("graph keys in the schema but not stored", emptySet<String>(), android.getValue("graph") - graph)
        val features = FeatureState().toJson().keys().asSequence().toSet()
        assertEquals("feature keys stored but not in the schema", emptySet<String>(), features - android.getValue("features"))
        assertEquals("feature keys in the schema but not stored", emptySet<String>(), android.getValue("features") - features)
    }

    @Test fun everyRowIsOnBothPlatformsOrSaysWhy() {
        for (r in rows) {
            val on = listOfNotNull("android".takeIf { r.android.isNotEmpty() }, "mac".takeIf { r.mac.isNotEmpty() })
            assertTrue("${r.id} is on no platform", on.isNotEmpty())
            if (on.size == 1) assertTrue("${r.id} is on ${on[0]} only without a reason", r.only == on[0] && (r.reason?.length ?: 0) >= 10)
            if (r.mirror) assertTrue("${r.id} is mirrored without a wire key", !r.wire.isNullOrEmpty())
            r.number?.let { assertTrue("${r.id}: unknown number $it", it in SettingsSpec.NUMBERS) }
        }
    }

    @Test fun modelDefaultsAreTheSchemas() {
        val g = GraphConfig()
        val S = SettingsSpec
        assertEquals(S.BAND_VERY_LOW.default.toInt(), g.bands.veryLow)
        assertEquals(S.BAND_LOW.default.toInt(), g.bands.low)
        assertEquals(S.BAND_TIGHT.default.toInt(), g.bands.tight)
        assertEquals(S.BAND_HIGH.default.toInt(), g.bands.high)
        assertEquals(S.BAND_VERY_HIGH.default.toInt(), g.bands.veryHigh)
        assertEquals(S.BAND_EXTREMELY_HIGH.default.toInt(), g.bands.extremelyHigh)
        assertEquals(S.GRAPH_HOURS.default.toInt(), g.defaultHours)
        assertEquals(S.RING_COUNT.default.toInt(), g.ringCount)
        assertEquals(S.GRID_HOURS.default.toInt(), g.gridHours)
        assertEquals(listOf(S.LINE.default.toInt()), g.lines)
        assertEquals(S.COUNTDOWN_HOURS.default.toInt(), SensorLife.DEFAULT_COUNTDOWN_HOURS)
        val a = AlertSettings()
        val threshold = mapOf(Kind.VERY_LOW to S.THRESHOLD_VERY_LOW, Kind.LOW to S.THRESHOLD_LOW, Kind.HIGH to S.THRESHOLD_HIGH,
            Kind.VERY_HIGH to S.THRESHOLD_VERY_HIGH, Kind.EXTREMELY_HIGH to S.THRESHOLD_EXTREMELY_HIGH,
            Kind.FALLING_FAST to S.THRESHOLD_RATE, Kind.RISING_FAST to S.THRESHOLD_RATE, Kind.NO_DATA to S.THRESHOLD_NO_DATA)
        for ((k, spec) in threshold) assertEquals("$k threshold", spec.default, a.cfg(k).value, 1e-9)
        for (k in Kind.ALL) assertTrue("$k repeat ${a.cfg(k).repeatMin} isn't a valid repeat", S.ALERT_REPEAT.valid(a.cfg(k).repeatMin.toDouble()))
        assertEquals(S.MUTE_INSULIN.default.toInt(), a.muteInsulinMin)
        assertEquals(S.MUTE_FALLING.default.toInt(), a.muteFallingMin)
        assertEquals(S.MUTE_CARBS.default.toInt(), a.muteCarbsMin)
        assertEquals(S.ACTION_MUTE.default.toInt(), a.actionMuteMin)
        assertEquals(S.ACTION_SLOW.default.toInt(), a.slowMin)
        val f = FeatureState()
        assertEquals(S.NOSTR_INTERVAL.default.toInt(), f.nostrIntervalMin)
        assertEquals(S.LIBREVIEW_INTERVAL.default.toInt(), f.libreviewIntervalMin)
    }

    /** min, max, every preset and special, one custom value in between; then values the spec rejects. */
    private fun valid(s: NumberSpec): List<Double> {
        val mid = if (s.custom) s.coerce((s.min + s.max) / 2) else null
        return (listOf(s.min, s.max) + s.presets + s.special.keys + listOfNotNull(mid)).filter(s::valid).distinct()
    }
    private fun invalid(s: NumberSpec) = listOf(s.max + s.step, s.min - s.step).filter { !s.valid(it) && it >= 0 }

    private val problems = mutableListOf<String>()

    /** Writes [v] through [put], reads it back through the settings file, and checks it against [s] (all problems listed). */
    private fun conforms(name: String, s: NumberSpec, put: (Double) -> Double) {
        for (v in valid(s)) put(v).let { if (kotlin.math.abs(it - v) > 1e-9) problems += "$name: valid $v came back as $it" }
        for (v in invalid(s)) put(v).let { if (!s.valid(it)) problems += "$name: invalid $v came back as $it, not a valid value" }
    }

    @Test fun everyValidNumberSurvivesTheSettingsFile() {
        val S = SettingsSpec
        fun graph(f: (GraphConfig) -> GraphConfig) = GraphConfig.fromJson(JSONObject(f(GraphConfig()).toJson().toString()))
        conforms("graph hours", S.GRAPH_HOURS) { v -> graph { it.copy(defaultHours = v.roundToInt()) }.defaultHours.toDouble() }
        conforms("place hours", S.PLACE_HOURS) { v ->
            (graph { it.copy(placeHours = mapOf(GraphPlace.WIDGETS to v.roundToInt())) }.placeHours[GraphPlace.WIDGETS] ?: 0).toDouble()
        }
        conforms("ring count", S.RING_COUNT) { v -> graph { it.copy(ringCount = v.roundToInt()) }.ringCount.toDouble() }
        conforms("grid hours", S.GRID_HOURS) { v -> graph { it.copy(gridHours = v.roundToInt()) }.gridHours.toDouble() }
        conforms("slot period", S.RING_WINDOW) { v ->
            graph { it.copy(slots = it.slots.toMutableList().also { l -> l[0] = l[0].copy(windowS = v.roundToLong()) }) }.slots[0].windowS.toDouble()
        }
        conforms("last-treatment fallback period", S.RING_WINDOW) { v ->
            graph { it.copy(lastElse = RingSpec(v.roundToLong())) }.lastElse?.windowS?.toDouble() ?: -1.0   // -1 = dropped to "nothing"
        }
        conforms("line", S.LINE) { v -> graph { it.copy(lines = listOf(v.roundToInt())) }.lines.firstOrNull()?.toDouble() ?: S.LINE.default }

        fun alerts(f: (AlertSettings) -> AlertSettings) = AlertSettings.fromJson(JSONObject(f(AlertSettings()).toJson().toString()))
        val threshold = mapOf(Kind.VERY_LOW to S.THRESHOLD_VERY_LOW, Kind.LOW to S.THRESHOLD_LOW, Kind.HIGH to S.THRESHOLD_HIGH,
            Kind.VERY_HIGH to S.THRESHOLD_VERY_HIGH, Kind.EXTREMELY_HIGH to S.THRESHOLD_EXTREMELY_HIGH,
            Kind.FALLING_FAST to S.THRESHOLD_RATE, Kind.RISING_FAST to S.THRESHOLD_RATE, Kind.NO_DATA to S.THRESHOLD_NO_DATA)
        for ((k, spec) in threshold) conforms("$k threshold", spec) { v ->
            alerts { it.copy(kinds = it.kinds + (k to it.cfg(k).copy(value = v))) }.cfg(k).value
        }
        conforms("repeat", S.ALERT_REPEAT) { v -> alerts { it.copy(kinds = it.kinds + (Kind.LOW to it.cfg(Kind.LOW).copy(repeatMin = v.roundToInt()))) }.cfg(Kind.LOW).repeatMin.toDouble() }
        conforms("quiet after insulin", S.MUTE_INSULIN) { v -> alerts { it.copy(muteInsulinMin = v.roundToInt()) }.muteInsulinMin.toDouble() }
        conforms("quiet falling", S.MUTE_FALLING) { v -> alerts { it.copy(muteFallingMin = v.roundToInt()) }.muteFallingMin.toDouble() }
        conforms("quiet after carbs", S.MUTE_CARBS) { v -> alerts { it.copy(muteCarbsMin = v.roundToInt()) }.muteCarbsMin.toDouble() }
        conforms("notification mute", S.ACTION_MUTE) { v -> alerts { it.copy(actionMuteMin = v.roundToInt()) }.actionMuteMin.toDouble() }
        conforms("slow down", S.ACTION_SLOW) { v -> alerts { it.copy(slowMin = v.roundToInt()) }.slowMin.toDouble() }

        fun features(f: (FeatureState) -> FeatureState) = FeatureState.fromJson(JSONObject(f(FeatureState()).toJson().toString()))
        conforms("nostr interval", S.NOSTR_INTERVAL) { v -> features { it.copy(nostrIntervalMin = v.roundToInt()) }.nostrIntervalMin.toDouble() }
        conforms("libreview interval", S.LIBREVIEW_INTERVAL) { v -> features { it.copy(libreviewIntervalMin = v.roundToInt()) }.libreviewIntervalMin.toDouble() }
        if (S.COUNTDOWN_HOURS.min.toInt()..S.COUNTDOWN_HOURS.max.toInt() != SensorLife.COUNTDOWN_RANGE) problems += "countdown range ${SensorLife.COUNTDOWN_RANGE}"
        assertEquals("numbers the models don't keep as the schema says", "", problems.joinToString("\n"))
    }

    @Test fun bandPresetsAreKeptInOrder() {
        // each band's presets with the others at their defaults survive Bands.sane() (the order rule)
        val S = SettingsSpec
        fun graph(f: (GraphConfig) -> GraphConfig) = GraphConfig.fromJson(JSONObject(f(GraphConfig()).toJson().toString())).bands
        for (v in S.BAND_VERY_LOW.presets) assertEquals(v.toInt(), graph { it.copy(bands = it.bands.copy(veryLow = v.toInt())) }.veryLow)
        for (v in S.BAND_LOW.presets) assertEquals(v.toInt(), graph { it.copy(bands = it.bands.copy(low = v.toInt())) }.low)
        for (v in S.BAND_TIGHT.presets) assertEquals(v.toInt(), graph { it.copy(bands = it.bands.copy(tight = v.toInt())) }.tight)
        for (v in S.BAND_HIGH.presets) assertEquals(v.toInt(), graph { it.copy(bands = it.bands.copy(high = v.toInt())) }.high)
        for (v in S.BAND_VERY_HIGH.presets) assertEquals(v.toInt(), graph { it.copy(bands = it.bands.copy(veryHigh = v.toInt())) }.veryHigh)
        for (v in S.BAND_EXTREMELY_HIGH.presets) assertEquals(v.toInt(), graph { it.copy(bands = it.bands.copy(extremelyHigh = v.toInt())) }.extremelyHigh)
    }

    @Test fun numberSpecParsesTypedText() {
        val days = SettingsSpec.RING_WINDOW
        assertEquals(10 * 86400.0, days.parse("10")!!, 0.0)
        assertEquals(null, days.parse("91"))
        assertEquals("10", days.text(10 * 86400.0))
        val rate = SettingsSpec.THRESHOLD_RATE
        assertEquals(1.5, rate.parse("1,5")!!, 1e-9)
        assertEquals(null, rate.parse("1.55"))
        assertEquals(null, rate.parse("abc"))
        assertEquals("once", SettingsSpec.ALERT_REPEAT.specialOf(0.0))
    }

    @Test fun optionListsAreTheSchemas() {
        val o = SettingsSpec.OPTIONS
        assertEquals(o["exportPeriod"], listOf("sensor") + app.mauri.sukrara.export.Export.DAYS.map { "$it" } + app.mauri.sukrara.export.Export.ALL)
        assertEquals(o["reportPeriod"], app.mauri.sukrara.ui.REPORT_WINDOWS.map { "${it / 86400}" })
        assertEquals(o["slotKind"], app.mauri.sukrara.graph.SlotKind.entries.map { it.name })
        assertEquals(o["tirRange"], app.mauri.sukrara.graph.TirRange.entries.map { it.name })
        assertEquals(o["lastPlace"], app.mauri.sukrara.graph.LastPlace.entries.map { it.name })
        val sounds = app.mauri.sukrara.alerts.AlertSounds
        assertEquals(o["soundPack"], sounds.PACKS + sounds.SYSTEM + sounds.NONE)
        assertEquals(o["settingsFrom"], WearerSettings.MODES)
        // Treatments on the graph: the kinds, the parts each has and its default (carbs: the point only)
        val M = app.mauri.sukrara.treatments.MarkShow
        assertEquals(o["markKind"], M.KINDS)
        assertEquals(o["markPart"], M.PARTS)
        for (k in M.KINDS) {
            assertEquals("markParts.$k", o["markParts.$k"], M.parts(k))
            assertEquals("markDefault.$k", o["markDefault.$k"], M.PARTS.filter { M.DEFAULT.has(k, it) })
        }
        assertEquals(listOf("point"), M.PARTS.filter { GraphConfig().marks.has("carbs", it) })
        // every combination survives the settings file; a part a kind doesn't have never sticks
        val mixed = M.DEFAULT.with("rapid", "point", false).with("carbs", "time", true).with("long", "line", true).with("note", "point", false)
        assertEquals("rapid:line+amount+time,long:point+amount+time,carbs:point+time,note:", mixed.text)
        assertEquals(mixed, GraphConfig.fromJson(JSONObject(GraphConfig(marks = mixed).toJson().toString())).marks)
    }
}
