// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import app.mauri.sukrara.R

import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager
import android.util.Log
import app.mauri.sukrara.treatments.Treatment
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single entry point the data layer calls: "something may have changed, refresh the widgets if needed".
 *
 * A widget only re-renders when what it shows changes: the latest reading, the minute of its age ("3 min ago"),
 * the treatments or the night mode (the graph bitmap has baked colours). While the screen is off nothing is rendered at all
 * (no launcher is showing it); the screen-on broadcast catches up with one render.
 */
object WidgetUpdater {
    /** What the widgets draw. Composition reads this (and collects it while a Glance session is alive). */
    data class Frame(val history: List<Reading>, val nowSec: Long, val treatments: List<Treatment> = emptyList())

    val frame = MutableStateFlow<Frame?>(null)

    data class Stats(val renders: Int = 0, val skippedSame: Int = 0, val skippedScreenOff: Int = 0,
        val lastReason: String = "", val lastRenderMs: Long = 0)
    val stats = MutableStateFlow(Stats())

    /** Off by default only for the battery comparison (DebugReceiver `screenOffSkip false`). */
    @Volatile var skipWhileScreenOff = true

    private val mutex = Mutex()
    private var lastKey: String? = null

    val widgets: List<Pair<GlanceAppWidget, Class<*>>> = listOf(
        ValueWidget() to ValueWidgetReceiver::class.java,
        GraphWidget() to GraphWidgetReceiver::class.java,
        CombinedWidget() to CombinedWidgetReceiver::class.java,
    )

    /** For composition when the process just started (system-initiated update): load from disk. */
    fun current(c: Context): Frame =
        frame.value ?: Frame(GlucoseStore.load(c), nowSec(), GlucoseStore.treatments(c)).also { frame.value = it }

    suspend fun refresh(c: Context, reason: String, force: Boolean = false) = mutex.withLock {
        val history = GlucoseStore.load(c)
        val treatments = GlucoseStore.treatments(c)
        val now = nowSec()
        val latest = history.lastOrNull()
        val night = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val key = "${latest?.epochSec}|${latest?.let { (now - it.epochSec) / 60 }}|$night|${history.size}|${treatments.hashCode()}"
        if (!force && key == lastKey) {
            stats.value = stats.value.run { copy(skippedSame = skippedSame + 1) }
            return@withLock
        }
        if (!force && skipWhileScreenOff && !c.getSystemService(PowerManager::class.java).isInteractive) {
            stats.value = stats.value.run { copy(skippedScreenOff = skippedScreenOff + 1) }
            return@withLock
        }
        lastKey = key
        frame.value = Frame(history, now, treatments)
        val t = System.currentTimeMillis()
        val mgr = GlanceAppWidgetManager(c)
        for ((widget, receiver) in widgets) {
            @Suppress("UNCHECKED_CAST")
            if (mgr.getGlanceIds(widget.javaClass).isNotEmpty()) widget.updateAll(c)
        }
        val ms = System.currentTimeMillis() - t
        stats.value = stats.value.run { copy(renders = renders + 1, lastReason = reason, lastRenderMs = ms) }
        Log.d("Sukrara.widgets", "widgets refreshed ($reason) in ${ms} ms")
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
