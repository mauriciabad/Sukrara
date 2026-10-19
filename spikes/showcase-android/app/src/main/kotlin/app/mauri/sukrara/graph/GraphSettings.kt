// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.content.Context
import android.content.SharedPreferences
import app.mauri.sukrara.core.Range
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Where a graph is shown; each place can follow the default window or have its own. */
enum class GraphPlace { APP, NOTIFICATION, WIDGETS }

/** Choices offered in Settings; a custom window is any whole number of hours in [WINDOW_HOURS_RANGE]. */
val WINDOW_HOURS = listOf(3, 6, 12, 24)
val WINDOW_HOURS_RANGE = 1..48
/** Hours between the vertical lines; 0 = none. */
val GRID_HOURS = listOf(0, 1, 2, 3, 4, 6, 12)
val RING_WINDOWS_S = listOf(12 * 3600L, 86400L, 3 * 86400L, 7 * 86400L, 14 * 86400L, 30 * 86400L)
/** A custom ring period: whole days in this range. */
val RING_DAYS_RANGE = 1..90
fun validRingWindow(s: Long) = s in RING_WINDOWS_S || (s % 86400 == 0L && s / 86400 in RING_DAYS_RANGE)

/** The chart's extra lines: 100 mg/dL unless the user changes them; at most [MAX_LINES], each in [LINE_RANGE]. */
val DEFAULT_LINES = listOf(100)
const val MAX_LINES = 6
val LINE_RANGE = 40..400

/** "100,120" → sorted distinct values inside [LINE_RANGE], at most [MAX_LINES]. */
fun parseLines(text: String): List<Int> =
    text.split(",").mapNotNull { it.trim().toIntOrNull()?.takeIf { v -> v in LINE_RANGE } }.distinct().sorted().take(MAX_LINES)

/**
 * Everything the graph and ring settings decide (investigation 16), for every role. Plain prefs + one StateFlow like
 * [app.mauri.sukrara.features.Features].
 */
data class GraphConfig(
    val bands: Bands = Bands(),
    val defaultHours: Int = 12,
    /** Per place; absent = [defaultHours]. */
    val placeHours: Map<GraphPlace, Int> = emptyMap(),
    /** How many slots show (0–3); all three slots (kind, window, faded), main first. */
    val ringCount: Int = 2,
    val slots: List<RingSpec> = DEFAULT_SLOTS,
    /** What a time-in-range ring counts in the middle. */
    val ringRange: TirRange = TirRange.IN_RANGE,
    /** The ring's segments clockwise from 12 o'clock. */
    val ringOrder: List<Band> = DEFAULT_RING_ORDER,
    /** The time-in-range rectangles + distribution beside the chart (each widget can turn it off on its own). */
    val side: Boolean = true,
    /** The crown over the 100 % beside the chart ([TirMark.column]). */
    val columnCrown: Boolean = true,
    /** The rings' rewards: laurel from 90 %, flowers from 95 %, crown at 100 % ([TirMark]). */
    val ringMarks: Boolean = true,
    /** Extra horizontal lines on the chart, mg/dL (100 by default; the user adds and removes them). */
    val lines: List<Int> = DEFAULT_LINES,
    /** Hours between the vertical lines at least ([GRID_HOURS]; small charts space them further apart), 0 = none. */
    val gridHours: Int = 1,
    /** The last-treatment slot beside the value ([LastPlace]; on by default, next to the number). */
    val lastPlace: LastPlace = LastPlace.NEAR_VALUE,
    /** What that slot shows while there's no recent treatment (null = nothing): a 30-day time-in-range ring by default. */
    val lastElse: RingSpec? = DEFAULT_LAST_ELSE,
) {
    /** [lastElse] with the ring settings applied. */
    fun lastElseRing() = lastElse?.copy(range = ringRange, order = ringOrder, marks = ringMarks)
    fun hours(p: GraphPlace) = placeHours[p] ?: defaultHours
    fun rings(count: Int = ringCount, range: TirRange = ringRange) =
        slots.take(count.coerceIn(0, 3)).map { it.copy(range = range, order = ringOrder, marks = ringMarks) }
    fun graphStyle(hours: Int, hour24: Boolean) =
        GraphStyle(windowS = hours * 3600L, bands = bands, side = side, hour24 = hour24, lines = lines, gridHours = gridHours,
            crown = columnCrown)

    fun toJson(): JSONObject = JSONObject()
        .put("veryLow", bands.veryLow).put("low", bands.low).put("tight", bands.tight).put("high", bands.high)
        .put("veryHigh", bands.veryHigh).put("extremelyHigh", bands.extremelyHigh)
        .put("defaultHours", defaultHours)
        .put("placeHours", JSONObject().apply { placeHours.forEach { (k, v) -> put(k.name, v) } })
        .put("ringCount", ringCount).put("ringRange", ringRange.name).put("ringOrder", ringOrder.joinToString(",") { it.name })
        .put("slots", slots.joinToString(",", transform = ::slotText))
        .put("side", side)
        .put("columnCrown", columnCrown).put("ringMarks", ringMarks)
        .put("lines", lines.joinToString(","))
        .put("gridHours", gridHours)
        .put("lastPlace", lastPlace.name)
        .put("lastElse", lastElse?.let(::slotText) ?: "")

    companion object {
        fun fromJson(o: JSONObject): GraphConfig {
            val d = GraphConfig()
            return GraphConfig(
                bands = Bands(o.optInt("veryLow", d.bands.veryLow), o.optInt("low", d.bands.low), o.optInt("tight", d.bands.tight),
                    o.optInt("high", d.bands.high), o.optInt("veryHigh", d.bands.veryHigh),
                    o.optInt("extremelyHigh", d.bands.extremelyHigh)).sane(),
                defaultHours = o.optInt("defaultHours", d.defaultHours).takeIf { it in WINDOW_HOURS_RANGE } ?: d.defaultHours,
                placeHours = o.optJSONObject("placeHours")?.let { p ->
                    GraphPlace.entries.mapNotNull { k -> p.optInt(k.name, 0).takeIf { it in WINDOW_HOURS_RANGE }?.let { k to it } }.toMap()
                } ?: emptyMap(),
                ringCount = o.optInt("ringCount", d.ringCount).coerceIn(0, 3),
                slots = o.optString("slots").split(",").mapNotNull(::parseSlot).takeIf { it.size == 3 } ?: d.slots,
                ringRange = runCatching { TirRange.valueOf(o.optString("ringRange")) }.getOrDefault(d.ringRange),
                ringOrder = o.optString("ringOrder").split(",").mapNotNull { runCatching { Band.valueOf(it) }.getOrNull() }
                    .takeIf { it.toSet().size == Band.entries.size && it.size == Band.entries.size } ?: d.ringOrder,
                side = o.optBoolean("side", d.side),
                columnCrown = o.optBoolean("columnCrown", d.columnCrown),
                ringMarks = o.optBoolean("ringMarks", d.ringMarks),
                lines = if (o.has("lines")) parseLines(o.optString("lines")) else d.lines,
                gridHours = o.optInt("gridHours", d.gridHours).takeIf { it in GRID_HOURS } ?: d.gridHours,
                lastPlace = runCatching { LastPlace.valueOf(o.optString("lastPlace")) }.getOrDefault(d.lastPlace),
                lastElse = if (o.has("lastElse")) parseSlot(o.optString("lastElse")) else d.lastElse,
            )
        }
    }
}

/**
 * One widget's own settings (Android widgets are configured per placement, so the same widget can be added twice
 * looking different). null = follow [GraphConfig].
 */
data class WidgetGraphConfig(
    val hours: Int? = null,
    val rings: Int? = null,
    val ringRange: TirRange? = null,
    val side: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        hours?.let { put("hours", it) }; rings?.let { put("rings", it) }; ringRange?.let { put("ringRange", it.name) }
        side?.let { put("side", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = WidgetGraphConfig(
            hours = o.optInt("hours", 0).takeIf { it in WINDOW_HOURS_RANGE },
            rings = if (o.has("rings")) o.optInt("rings").coerceIn(0, 3) else null,
            ringRange = runCatching { TirRange.valueOf(o.optString("ringRange")) }.getOrNull(),
            side = if (o.has("side")) o.optBoolean("side") else null,
        )
    }
}

/** One slot as "KIND:windowS:faded", e.g. "TIR:86400:1". */
private fun slotText(s: RingSpec) = "${s.kind.name}:${s.windowS}:${if (s.faded) 1 else 0}"

private fun parseSlot(e: String): RingSpec? {
    val p = e.split(":")
    val kind = runCatching { SlotKind.valueOf(p[0]) }.getOrNull() ?: return null
    val w = p.getOrNull(1)?.toLongOrNull()?.takeIf(::validRingWindow) ?: return null
    return RingSpec(w, kind = kind, faded = p.getOrNull(2) != "0")
}

/** The last-treatment slot's default fallback: time in range over 30 days, faded like a second slot. */
val DEFAULT_LAST_ELSE = RingSpec(30 * 86400L)

/** Today and the last week as time in range; the third slot (off by default) the 14-day GMI, as on the report. */
val DEFAULT_SLOTS = listOf(RingSpec(86400L), RingSpec(7 * 86400L), RingSpec(14 * 86400L, kind = SlotKind.GMI))

object GraphSettings {
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(GraphConfig())
    val state: StateFlow<GraphConfig> = _state
    val value: GraphConfig get() = _state.value

    @Synchronized fun init(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("graph", Context.MODE_PRIVATE)
        prefs = p
        _state.value = p.getString("config", null)?.let { runCatching { GraphConfig.fromJson(JSONObject(it)) }.getOrNull() } ?: GraphConfig()
        Range.bands = value.bands
    }

    @Synchronized fun update(f: (GraphConfig) -> GraphConfig): GraphConfig {
        val s = GraphConfig.fromJson(f(_state.value).toJson())   // the same validation as loading
        prefs?.edit()?.putString("config", s.toJson().toString())?.apply()
        _state.value = s
        Range.bands = s.bands
        return s
    }

    private fun widgetPrefs(c: Context) = c.applicationContext.getSharedPreferences("graph-widgets", Context.MODE_PRIVATE)

    fun widget(c: Context, appWidgetId: Int): WidgetGraphConfig =
        widgetPrefs(c).getString("$appWidgetId", null)?.let { runCatching { WidgetGraphConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: WidgetGraphConfig()

    fun setWidget(c: Context, appWidgetId: Int, w: WidgetGraphConfig) =
        widgetPrefs(c).edit().putString("$appWidgetId", w.toJson().toString()).apply()

    fun forgetWidgets(c: Context, ids: IntArray) = widgetPrefs(c).edit().apply { ids.forEach { remove("$it") } }.apply()
}

/**
 * The graph palette on Android surfaces: ink, grid and background from the Material 3 dynamic-colour system
 * resources (so a widget bitmap matches its themed frame), range colours from [GraphPalette].
 */
fun systemPalette(c: Context, dark: Boolean = (c.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES): GraphPalette {
    fun res(light: Int, night: Int) = c.getColor(if (dark) night else light)
    return GraphPalette.of(dark).copy(
        bg = res(android.R.color.system_surface_container_light, android.R.color.system_surface_container_dark),
        ink = res(android.R.color.system_on_surface_light, android.R.color.system_on_surface_dark),
        inkSoft = res(android.R.color.system_on_surface_variant_light, android.R.color.system_on_surface_variant_dark),
        grid = res(android.R.color.system_outline_variant_light, android.R.color.system_outline_variant_dark),
    )
}
