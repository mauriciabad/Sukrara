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
enum class GraphPlace(val label: String) { APP("App"), NOTIFICATION("Notification"), WIDGETS("Widgets") }

/** Choices offered in Settings. */
val WINDOW_HOURS = listOf(3, 6, 12, 24)
val RING_WINDOWS_S = listOf(12 * 3600L, 86400L, 3 * 86400L, 7 * 86400L, 14 * 86400L)

/**
 * Everything the graph and ring settings decide (investigation 16), for every role. Plain prefs + one StateFlow like
 * [app.mauri.sukrara.features.Features].
 */
data class GraphConfig(
    val bands: Bands = Bands(),
    val defaultHours: Int = 12,
    /** Per place; absent = [defaultHours]. */
    val placeHours: Map<GraphPlace, Int> = emptyMap(),
    /** How many rings show (0–3); the windows of all three, main first. */
    val ringCount: Int = 2,
    val ringWindows: List<Long> = listOf(86400L, 7 * 86400L, 14 * 86400L),
    val ringRange: TirRange = TirRange.IN_RANGE,
    /** The time-in-range rectangles + distribution beside the chart (each widget can turn it off on its own). */
    val side: Boolean = true,
) {
    fun hours(p: GraphPlace) = placeHours[p] ?: defaultHours
    fun rings(count: Int = ringCount, range: TirRange = ringRange) = ringWindows.take(count.coerceIn(0, 3)).map { RingSpec(it, range) }
    fun graphStyle(hours: Int, hour24: Boolean) =
        GraphStyle(windowS = hours * 3600L, bands = bands, side = side, hour24 = hour24)

    fun toJson(): JSONObject = JSONObject()
        .put("veryLow", bands.veryLow).put("low", bands.low).put("tight", bands.tight).put("high", bands.high)
        .put("veryHigh", bands.veryHigh)
        .put("defaultHours", defaultHours)
        .put("placeHours", JSONObject().apply { placeHours.forEach { (k, v) -> put(k.name, v) } })
        .put("ringCount", ringCount).put("ringWindows", ringWindows.joinToString(",")).put("ringRange", ringRange.name)
        .put("side", side)

    companion object {
        fun fromJson(o: JSONObject): GraphConfig {
            val d = GraphConfig()
            return GraphConfig(
                bands = Bands(o.optInt("veryLow", d.bands.veryLow), o.optInt("low", d.bands.low), o.optInt("tight", d.bands.tight),
                    o.optInt("high", d.bands.high), o.optInt("veryHigh", d.bands.veryHigh)).sane(),
                defaultHours = o.optInt("defaultHours", d.defaultHours).takeIf { it in WINDOW_HOURS } ?: d.defaultHours,
                placeHours = o.optJSONObject("placeHours")?.let { p ->
                    GraphPlace.entries.mapNotNull { k -> p.optInt(k.name, 0).takeIf { it in WINDOW_HOURS }?.let { k to it } }.toMap()
                } ?: emptyMap(),
                ringCount = o.optInt("ringCount", d.ringCount).coerceIn(0, 3),
                ringWindows = o.optString("ringWindows").split(",").mapNotNull { it.toLongOrNull() }
                    .filter { it in RING_WINDOWS_S }.takeIf { it.size == 3 } ?: d.ringWindows,
                ringRange = runCatching { TirRange.valueOf(o.optString("ringRange")) }.getOrDefault(d.ringRange),
                side = o.optBoolean("side", d.side),
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
            hours = o.optInt("hours", 0).takeIf { it in WINDOW_HOURS },
            rings = if (o.has("rings")) o.optInt("rings").coerceIn(0, 3) else null,
            ringRange = runCatching { TirRange.valueOf(o.optString("ringRange")) }.getOrNull(),
            side = if (o.has("side")) o.optBoolean("side") else null,
        )
    }
}

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
