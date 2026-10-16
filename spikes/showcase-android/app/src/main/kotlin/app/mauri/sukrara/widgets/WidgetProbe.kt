// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.appwidget.AppWidgetManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Measures the size a launcher really gives a graph widget.
 *
 * Glance's Exact mode composes for the sizes the launcher *reports* (OPTION_APPWIDGET_SIZES), and launchers can be
 * wrong: Pixel Launcher on the user's Pixel 10 Pro (6-column grid) reports 296×202 dp for a 4×2 widget it lays out
 * at 244×149 dp, so the graph was either clipped or, scaled to fit, stretched. The app can't read the real size, but
 * the launcher's AppWidgetHostView knows it: from a size → RemoteViews map it applies the layout that fits its real
 * laid-out size with the nearest size (RemoteViews.findBestFitLayout, re-picked in onLayout).
 *
 * So a probe sends up to 16 tiny layouts, each an image whose content:// URI names its size, and [ProbeProvider]
 * sees which one the launcher loads. Round 1 brackets the size (100…55 % of the reported one), round 2 narrows it
 * to ≈ 4 %. Runs only when a graph widget is placed or resized (or no measurement exists yet), takes about a second,
 * and the widget is blank meanwhile. The result is kept per widget and reported size.
 */
object WidgetProbe {
    private const val TAG = "Sukrara.widgets"
    private const val COARSE_STEP = 0.15f
    private val COARSE = listOf(1f, 1f - COARSE_STEP, 1f - 2 * COARSE_STEP, 1f - 3 * COARSE_STEP)

    private val pending = ConcurrentHashMap<String, CompletableDeferred<SizeF>>()
    private val mutex = Mutex()

    private fun prefs(c: Context) = c.getSharedPreferences("widget-sizes", Context.MODE_PRIVATE)
    private fun key(id: Int, reported: SizeF) = "$id@${reported.width.roundToInt()}x${reported.height.roundToInt()}"

    /** The measured size of widget [id] for the layout Glance composes at [reported], or null if not measured. */
    fun actual(c: Context, id: Int, reported: DpSize): DpSize? =
        prefs(c).getString(key(id, SizeF(reported.width.value, reported.height.value)), null)?.split('x')
            ?.let { (w, h) -> DpSize(w.toFloat().dp, h.toFloat().dp) }

    fun forget(c: Context, ids: IntArray) {
        val e = prefs(c).edit()
        prefs(c).all.keys.filter { k -> ids.any { k.startsWith("$it@") } }.forEach(e::remove)
        e.apply()
    }

    fun placed(c: Context): List<Int> {
        val mgr = AppWidgetManager.getInstance(c)
        return GRAPH_RECEIVERS.flatMap { mgr.getAppWidgetIds(android.content.ComponentName(c, it)).toList() }
    }

    /** Graph widgets placed in the current orientation that have no measurement yet. */
    fun unmeasured(c: Context): List<Int> {
        val mgr = AppWidgetManager.getInstance(c)
        return placed(c).filter { id -> reported(c, mgr, id)?.let { prefs(c).getString(key(id, it), null) == null } == true }
    }

    /** Measures widget [id] (two probe rounds). Returns false when the launcher didn't answer (e.g. not on screen). */
    suspend fun measure(c: Context, id: Int): Boolean = mutex.withLock {
        val mgr = AppWidgetManager.getInstance(c)
        val reported = reported(c, mgr, id) ?: return false.also { Log.w(TAG, "widget $id: no reported size to probe") }
        val first = round(c, mgr, id, COARSE.map { reported.width * it }, COARSE.map { reported.height * it })
            ?: return false.also { Log.w(TAG, "widget $id: the launcher didn't answer the probe") }
        fun fine(chosen: Float, full: Float) =
            if (chosen >= full) listOf(full) else (0..3).map { chosen + full * COARSE_STEP * it / 4 }
        val second = round(c, mgr, id, fine(first.width, reported.width), fine(first.height, reported.height))
            ?: first
        prefs(c).edit().putString(key(id, reported), "${second.width}x${second.height}").apply()
        Log.i(TAG, "widget $id: launcher reports ${reported.width}x${reported.height} dp, " +
            "lays it out at ≈ ${second.width}x${second.height} dp")
        true
    }

    private suspend fun round(c: Context, mgr: AppWidgetManager, id: Int, widths: List<Float>, heights: List<Float>): SizeF? {
        val nonce = UUID.randomUUID().toString()
        val answer = CompletableDeferred<SizeF>()
        pending[nonce] = answer
        try {
            val views = widths.distinct().flatMap { w -> heights.distinct().map { h -> SizeF(w, h) } }.associateWith { s ->
                RemoteViews(c.packageName, R.layout.widget_probe).apply {
                    setImageViewUri(R.id.probe, Uri.parse("content://${c.packageName}.widgetprobe/$nonce/${s.width}/${s.height}"))
                }
            }
            mgr.updateAppWidget(id, RemoteViews(views))
            return withTimeoutOrNull(3_000) { answer.await() }
        } finally {
            pending.remove(nonce)
        }
    }

    /** The size the launcher reports for the current orientation (the portrait or the landscape entry). */
    private fun reported(c: Context, mgr: AppWidgetManager, id: Int): SizeF? {
        val sizes = mgr.getAppWidgetOptions(id).getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            ?.takeIf { it.isNotEmpty() } ?: return null
        val portrait = c.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        return if (portrait) sizes.minBy { it.width / it.height } else sizes.maxBy { it.width / it.height }
    }

    private val GRAPH_RECEIVERS = listOf(GraphWidgetReceiver::class.java, CombinedWidgetReceiver::class.java)

    /** Called by [ProbeProvider] when the launcher loads a probe image. */
    internal fun answered(nonce: String, w: Float, h: Float) {
        Log.d(TAG, "probe answer ${w}x$h (${if (pending.containsKey(nonce)) "current" else "stale"})")
        pending[nonce]?.complete(SizeF(w, h))
    }
}

/**
 * Serves the probe images (a 1×1 transparent PNG) and tells [WidgetProbe] which one the launcher loaded. Exported
 * so the launcher can read it; it answers only URIs of a probe in progress (random nonce) and returns no data.
 */
class ProbeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val seg = uri.pathSegments
        if (seg.size == 3) runCatching { WidgetProbe.answered(seg[0], seg[1].toFloat(), seg[2].toFloat()) }
        val ctx = context ?: return null
        val png = File(ctx.cacheDir, "probe.png")
        if (!png.exists()) png.outputStream().use { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ParcelFileDescriptor.open(png, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
