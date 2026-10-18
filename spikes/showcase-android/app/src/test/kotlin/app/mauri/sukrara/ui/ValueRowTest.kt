// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mauri.sukrara.graph.Bands
import app.mauri.sukrara.graph.GlucoseMetrics
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.LastPlace
import app.mauri.sukrara.graph.RingSpec
import app.mauri.sukrara.graph.RoundedFont
import app.mauri.sukrara.graph.Slots
import app.mauri.sukrara.graph.Synthetic
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The app panel's header (value + slots, [ValueAndSlots]): the slots keep their designed size (68 dp) whatever the
 * value; a wide value shrinks instead (the user, 2026-10-18: "the main ring is smaller than it should be … the number
 * should be the one shrinking"). Checked at the user's phone width (a 1080 px / 420 dpi screen: 347 dp inside the
 * card) and the narrowest supported phone (360 dp: 296 dp inside), with the widest value. The cards drawn on a canvas
 * (widgets, lock screen, notification: [GlucoseCard]) size their rings from the height: their rings must come out
 * pixel-identical with "388 ↗" and "68 →". Writes the `value-row` sheet to gallery.out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w420dp-h900dp-xxhdpi")
class ValueRowTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val now = 1_792_300_000L
    private val data = Synthetic.days(now, 15)
    private val rings = listOf(RingSpec(86400), RingSpec(7 * 86400L), RingSpec(14 * 86400L)).mapIndexed { i, s ->
        Triple(i, s, GlucoseMetrics.of(data, now - s.windowS, now, Bands()))
    }

    private class Case(val width: Float, val number: String, val fontScale: Float)

    // font scales are covered by ValueFitTest (Robolectric's Text ignores a LocalDensity font scale)
    private val cases = listOf(347f, 296f).flatMap { w -> listOf("188", "388").map { Case(w, it, 1f) } }

    @Test fun slotsKeepTheirSize() {
        RoundedFont.init(RuntimeEnvironment.getApplication())
        val pal = GraphPalette.DARK
        compose.setContent {
            Column(Modifier.background(Color(0xFF0E0E10)).padding(8.dp)) {
                for (c in cases) {
                    Text("${c.width.toInt()} dp · ${c.number} ↗ · font ×${c.fontScale}", color = Color.Gray, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp))
                    val base = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(base.density, c.fontScale)) {
                        Column(Modifier.width(c.width.dp + 32.dp).background(Color(0xFF1C1B1F)).padding(16.dp)) {
                            ValueAndSlots(c.number, { fit -> Slots.arrange(rings, LastPlace.OFF, null, null, fit) },
                                value = { scale -> ValueNumber(c.number, 1.5, Color(0xFFF2B33D), false, scale) },
                                slot = { item ->
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawIntoCanvas { Slots.draw(it.nativeCanvas, item, size.width / 2, size.height / 2,
                                            size.minDimension / 2, now, pal, density) }
                                    }
                                })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val out = File(System.getProperty("gallery.out") ?: "build/gallery").also { it.mkdirs() }
        File(out, "value-row.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val slots = compose.onAllNodesWithTag("slot").fetchSemanticsNodes()
        val d = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        for (n in slots) {
            assertEquals("slot width", 68f, n.size.width / d, 0.5f)
            assertEquals("slot height", 68f, n.size.height / d, 0.5f)
        }
    }

    /** The canvas card at the smallest value placements: the main ring's pixels don't depend on the value. */
    @Test fun cardRingsIgnoreTheValue() {
        RoundedFont.init(RuntimeEnvironment.getApplication())
        val d = 2f
        val fixed = { r: RingSpec, b: Bands -> GlucoseMetrics.of(data, now - r.windowS, now, b) }
        val sizes = listOf(150f to 60f, 230f to 60f, 300f to 150f)   // 2×1, 3×1, 4×2 value widgets
        for ((w, h) in sizes) {
            fun draw(sgv: Int, rate: Double): Bitmap {
                val bmp = Bitmap.createBitmap((w * d).toInt(), (h * d).toInt(), Bitmap.Config.ARGB_8888)
                val readings = data.dropLast(1) + data.last().copy(sgv = sgv, rate = rate)
                app.mauri.sukrara.graph.GlucoseCard.draw(android.graphics.Canvas(bmp), w * d, h * d, readings, emptyList(), now,
                    app.mauri.sukrara.graph.CardStyle(graph = null), GraphPalette.DARK, d, metricsOf = fixed, alerts = null)
                return bmp
            }
            val a = draw(388, 1.5); val b = draw(68, 0.0)
            // the main ring: rightmost, its designed diameter (min(96 % of the height, 84 dp)) whatever the value
            val x0 = a.width - (minOf(h * 0.96f, 84f) * d).toInt()
            var diff = 0
            for (y in 0 until a.height) for (x in x0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) diff++
            assertEquals("${w.toInt()}×${h.toInt()}: ring pixels changed with the value", 0, diff)
        }
    }
}
