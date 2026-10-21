// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager
import android.util.Log
import android.util.SizeF
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.treatments.Treatment
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The single entry point the data layer calls: "something may have changed, refresh the widgets if needed".
 *
 * A widget only re-renders when what it shows changes: the latest reading, its freshness step
 * ([app.mauri.sukrara.graph.Freshness]), the 5-min ring refresh, the treatments, the graph settings or the night mode
 * (the bitmap has baked colours). While the screen is off nothing is rendered at all
 * (no launcher is showing it); the screen-on broadcast catches up with one render.
 */
object WidgetUpdater {
    /** What the widgets draw. Composition reads this (and collects it while a Glance session is alive). */
    data class Frame(val history: List<Reading>, val nowSec: Long, val treatments: List<Treatment> = emptyList(),
        val generation: Int = 0)

    val frame = MutableStateFlow<Frame?>(null)

    data class Stats(val renders: Int = 0, val skippedSame: Int = 0, val skippedScreenOff: Int = 0,
        val lastReason: String = "", val lastRenderMs: Long = 0)
    val stats = MutableStateFlow(Stats())

    /** Off by default only for the battery comparison (DebugReceiver `screenOffSkip false`). */
    @Volatile var skipWhileScreenOff = true

    private val mutex = Mutex()
    private var lastKey: String? = null
    private var generation = 0
    /** appWidgetId → when a probe was last tried, so a launcher that doesn't answer (not on screen) is retried every 2 min, not every refresh. */
    private val probed = HashMap<Int, Long>()

    fun allowProbe(id: Int) = synchronized(probed) { probed.remove(id) }

    val widgets: List<Pair<GlanceAppWidget, Class<*>>> = listOf(
        ValueWidget() to ValueWidgetReceiver::class.java,
        GraphWidget() to GraphWidgetReceiver::class.java,
        CombinedWidget() to CombinedWidgetReceiver::class.java,
        LockscreenWidget() to LockscreenWidgetReceiver::class.java,
    )

    /** For composition when the process just started (system-initiated update): load from disk. */
    fun current(c: Context): Frame =
        frame.value ?: Frame(RecentHistory.readings(c), Proto.nowS(), RecentHistory.treatments(c)).also { frame.value = it }

    suspend fun refreshSafely(c: Context, reason: String, force: Boolean = false) =
        runCatching { refresh(c, reason, force) }.onFailure { Log.w("Sukrara.widgets", "refresh ($reason): $it") }

    suspend fun refresh(c: Context, reason: String, force: Boolean = false) = mutex.withLock {
        val history = RecentHistory.readings(c)
        val treatments = RecentHistory.treatments(c)
        val now = Proto.nowS()
        val latest = history.lastOrNull()
        val night = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        // Redraw for a new reading, a freshness step (late → stale → …), the 5-min ring refresh, theme, treatments or
        // settings (graph and alerts: the badge, the mute lines and the last-treatment ring follow the alert rules). A fresh card shows no age, so nothing ticks in between.
        val fresh = latest?.let { app.mauri.sukrara.graph.Freshness.of(now - it.t) }
        val key = "${latest?.t}|$fresh|${now / 300}|$night|${java.util.Locale.getDefault()}|${history.size}|${treatments.hashCode()}|" +
            "${app.mauri.sukrara.graph.GraphSettings.value.hashCode()}|${app.mauri.sukrara.alerts.Alerts.settings.value.hashCode()}|" +
            "${app.mauri.sukrara.alerts.Alerts.paused.value.hashCode()}"
        if (!force && key == lastKey) {
            stats.value = stats.value.run { copy(skippedSame = skippedSame + 1) }
            return@withLock
        }
        if (!force && skipWhileScreenOff && !c.getSystemService(PowerManager::class.java).isInteractive) {
            stats.value = stats.value.run { copy(skippedScreenOff = skippedScreenOff + 1) }
            return@withLock
        }
        lastKey = key
        // A graph widget without a measured size (just placed, resized, or the app was updated): measure it first,
        // while the launcher is on screen to answer.
        val interactive = c.getSystemService(PowerManager::class.java).isInteractive
        for (id in if (interactive) WidgetProbe.unmeasured(c) else emptyList()) {
            val due = synchronized(probed) { (probed[id] ?: 0) < System.currentTimeMillis() - 2 * 60_000 }
            if (!due) continue
            synchronized(probed) { probed[id] = System.currentTimeMillis() }
            WidgetProbe.measure(c, id)
        }
        frame.value = Frame(history, now, treatments, ++generation)
        val t = System.currentTimeMillis()
        val mgr = GlanceAppWidgetManager(c)
        for ((widget, receiver) in widgets) {
            @Suppress("UNCHECKED_CAST")
            if (widget is LockscreenWidget) LockscreenWidget.updateAll(c)
            else if (mgr.getGlanceIds(widget.javaClass).isNotEmpty()) widget.updateAll(c)
        }
        val ms = System.currentTimeMillis() - t
        stats.value = stats.value.run { copy(renders = renders + 1, lastReason = reason, lastRenderMs = ms) }
        Log.d("Sukrara.widgets", "widgets refreshed ($reason) in ${ms} ms")
    }

    /**
     * Every placed widget and the sizes its launcher reports (dp): `sizes` = OPTION_APPWIDGET_SIZES (what Exact
     * widgets compose for), `min`/`max` = width × height of the older min/max options. For the DebugReceiver.
     */
    fun placements(c: Context): JSONArray {
        val mgr = AppWidgetManager.getInstance(c)
        val out = JSONArray()
        for ((widget, receiver) in widgets) for (id in mgr.getAppWidgetIds(ComponentName(c, receiver))) {
            val o = mgr.getAppWidgetOptions(id)
            val sizes = o.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            out.put(JSONObject().put("id", id).put("widget", widget.javaClass.simpleName)
                .put("sizes", JSONArray(sizes.orEmpty().map { "${it.width.roundToInt()}x${it.height.roundToInt()}" }))
                .put("min", "${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}")
                .put("max", "${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)}x${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)}"))
        }
        return out
    }

    /** Android 15+ generated previews for the widget picker. Rate-limited by the system (~2 calls/hour/provider). */
    suspend fun publishPreviews(c: Context): String {
        val mgr = GlanceAppWidgetManager(c)
        val out = ArrayList<String>()
        for ((_, receiver) in widgets) {
            @Suppress("UNCHECKED_CAST")
            val ok = runCatching {
                mgr.setWidgetPreviews((receiver as Class<out androidx.glance.appwidget.GlanceAppWidgetReceiver>).kotlin)
            }
            out += "${receiver.simpleName}=${ok.getOrElse { it.message }}"
        }
        return out.joinToString()
    }
}
