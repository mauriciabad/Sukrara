// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.ui.GlucoseReportContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Investigation 18: the report card on synthetic data (14 days), light and dark, for docs/img/18. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h1400dp-xxhdpi")
class ReportGallery {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File(System.getProperty("gallery.out") ?: "build/gallery").also { it.mkdirs() }

    private fun render(dark: Boolean, edges: Boolean = false) {
        val now = java.util.Calendar.getInstance().run { clear(); set(2026, 9, 17, 22, 14); timeInMillis / 1000 }
        // edges: shares next to their goals (very high 5.2 %, in range 70.2 %, very low 0.96 %, 69.7 % coverage), for the
        // rounding rule (ShareText, spikes/graph-fixtures/share)
        val m = if (edges) GlucoseMetrics(TimeInRange(0.0096, 0.0424, 0.40, 0.302, 0.194, 0.052, 0.697), 150.0, 45.0, 4000)
            else GlucoseMetrics.of(Synthetic.days(now, 31), now - 14 * 86400L, now, Bands())
        val act = compose.activity
        val view = androidx.compose.ui.platform.ComposeView(act).apply {
            setContent {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        androidx.compose.foundation.layout.Box(Modifier.width(400.dp).padding(16.dp)) {
                            GlucoseReportContent(m, 14 * 86400L, {}, Bands(), GraphPalette.of(dark))
                        }
                    }
                }
            }
        }
        compose.runOnUiThread { act.setContentView(view) }
        compose.waitForIdle()
        // captureToImage waits for a hardware frame Robolectric never delivers: lay out and draw in software instead.
        val crop = compose.runOnUiThread<Bitmap> {
            val w = (400 * act.resources.displayMetrics.density).toInt()
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File(out, "report-${if (edges) "edges-" else ""}${if (dark) "dark" else "light"}.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun light() = render(false)
    @Test fun dark() = render(true)
    @Test fun edges() = render(false, edges = true)
}
