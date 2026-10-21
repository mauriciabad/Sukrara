// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.Reading
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory
import kotlin.math.sin

/**
 * The cost of one frame of a scrolled chart (the app panel's [CardPart.GRAPH] with a [ChartView]) over the wearer's
 * whole store: 31 days of 1-minute readings (44 640), as the panel draws it on every frame of a drag or fling, plus the
 * y range it animates towards ([GlucoseCard.yRange]). Prints the median / p95 time and the bytes allocated per frame
 * (JVM, Robolectric's native canvas: a proxy for the phone, not its numbers) and fails only far above a frame's budget,
 * so a change that makes scrolling scan or sort the whole history again shows up here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h900dp-xxhdpi")
class ChartScrollPerfTest {
    @Test fun scrolledFrameStaysCheap() {
        val ctx = RuntimeEnvironment.getApplication()
        RoundedFont.init(ctx)
        val now = 1_800_000_000L
        val days = 31
        val readings = (0 until days * 1440).map { i ->
            val t = now - (days * 1440 - i) * 60L
            Reading(t, (130 + 70 * sin(i / 180.0) + 25 * sin(i / 37.0)).toInt(), 0.0)
        }
        val d = 2.625f
        val w = 379 * d; val h = PANEL_CHART * d
        val bmp = createBitmap(w.toInt(), h.toInt())
        val cv = Canvas(bmp)
        val st = CardStyle(sensorTime = false)
        val pal = GraphPalette.LIGHT
        val alerts = AlertSettings()
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        fun frame(k: Int) {
            val end = now - k * 97L   // ~ a fling's step at 60 fps over a 12 h window
            val y = GlucoseCard.yRange(readings, end, st.graph!!)
            GlucoseCard.draw(cv, w, h, readings, emptyList(), now, st, pal, d, CardSurface.TEST, CardPart.GRAPH,
                alerts = alerts, sensor = null, paused = emptyMap(), view = ChartView(end, y, 1f))
        }
        repeat(300) { frame(it) }   // warm up (JIT)
        val times = LongArray(600)
        val a0 = bean.getThreadAllocatedBytes(tid)
        for (k in times.indices) { val t0 = System.nanoTime(); frame(300 + k); times[k] = System.nanoTime() - t0 }
        val perFrame = (bean.getThreadAllocatedBytes(tid) - a0) / times.size
        times.sort()
        val med = times[times.size / 2] / 1e6; val p95 = times[times.size * 95 / 100] / 1e6
        println("scrolled frame over ${readings.size} readings: median %.2f ms, p95 %.2f ms, %d KB allocated per frame"
            .format(java.util.Locale.US, med, p95, perFrame / 1024))
        // the same frames' data work alone (window, time in range, distribution, y range): the rest is drawing
        val b = st.graph!!.bands.sane()
        val s0 = bean.getThreadAllocatedBytes(tid); val c0 = System.nanoTime()
        for (k in 0 until 600) {
            val end = now - (300 + k) * 97L; val from = end - 12 * 3600L
            val pts = GlucoseStats.window(readings, from - 60, end)
            val tir = GlucoseStats.timeInRange(pts, from, end, b)
            GlucoseStats.profile(pts, from, end, b, tir)
            GlucoseCard.yRange(readings, end, st.graph!!)
        }
        println("  of which data: %.2f ms, %d KB per frame".format(java.util.Locale.US, (System.nanoTime() - c0) / 600 / 1e6,
            (bean.getThreadAllocatedBytes(tid) - s0) / 600 / 1024))
        bmp.recycle()
        assertTrue("a scrolled frame takes %.1f ms (median): far above a 16 ms frame".format(java.util.Locale.US, med), med < 16)
    }

    private companion object { const val PANEL_CHART = 210f }
}
