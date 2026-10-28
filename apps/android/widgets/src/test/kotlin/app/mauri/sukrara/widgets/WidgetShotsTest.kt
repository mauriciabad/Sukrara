// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPalette
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Roborazzi goldens of the three widgets through [renderWidget] (synthetic readings only) at 2×2 and 4×2 on one phone:
 * light + dark × default + largest font (a taller cell) while fresh; stale (6 min) and hidden (25 min) in light.
 * `recordRoborazziDebug` writes src/test/snapshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetShotsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-10-27T08:00:00Z")
    private val savedZone = TimeZone.getDefault()
    private val savedLocale = Locale.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    /** A synthetic day ending [age] before now (every 5 min, a slow wave around 140). */
    private fun frame(
        age: Duration,
        dark: Boolean,
    ): WidgetFrame {
        val end = now.minus(age)
        val readings =
            (288 downTo 0).map { i ->
                val t = end.minusSeconds(i * 300L)
                val v = 140 + 60 * sin(2 * PI * i / 96.0) + 8 * sin(2 * PI * i / 11.0)
                Reading(t, v.roundToInt(), 0.6, SourceId("synthetic"))
            }
        val g =
            GlucoseState.Initial.copy(
                role = Role.Wearer,
                latest = readings.last(),
                recent = readings,
                sensor = SensorState.Active(now.plus(Duration.ofDays(6))),
            )
        return WidgetFrame(g, emptyList(), GraphConfig(), WidgetPlacements(), now, dark)
    }

    private fun shoot(
        kind: WidgetKind,
        cell: String,
        wDp: Float,
        hDp: Float,
        frame: WidgetFrame,
        name: String,
    ) {
        val card = renderWidget(context, kind, wDp, hDp, frame, WidgetOverrides())
        val bmp = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(GraphPalette.of(frame.dark).bg)
            drawBitmap(card, 0f, 0f, null)
        }
        bmp.captureRoboImage("src/test/snapshots/widget-${kind.name.lowercase()}-$cell-$name.png")
        val description = describeCard(context, kind, frame, WidgetOverrides())
        assertTrue("$kind $name has a description", description.isNotBlank())
    }

    @Test
    fun `the description takes the value from the renderer's face`() {
        val seen = mutableListOf<CardSurface>()
        GlucoseCard.faceObserver = { surface, _ -> seen += surface }
        try {
            describeCard(context, WidgetKind.COMBINED, frame(Duration.ofMinutes(1), false), WidgetOverrides())
        } finally {
            GlucoseCard.faceObserver = null
        }
        assertTrue(CardSurface.WIDGET_DESCRIPTION in seen)
    }

    @Test
    fun widgets() {
        val cells = listOf("2x2" to (170f to 170f), "4x2" to (320f to 170f))
        for (kind in WidgetKind.entries) {
            for ((cell, size) in cells) {
                val (w, h) = size
                for (dark in listOf(false, true)) {
                    for (large in listOf(false, true)) {
                        val name = "fresh-${if (dark) "dark" else "light"}${if (large) "-largest" else ""}"
                        shoot(kind, cell, w, if (large) h * 1.3f else h, frame(Duration.ofMinutes(1), dark), name)
                    }
                }
                shoot(kind, cell, w, h, frame(Duration.ofMinutes(6), false), "stale-light")
                shoot(kind, cell, w, h, frame(Duration.ofMinutes(25), false), "hidden-light")
            }
        }
    }
}
