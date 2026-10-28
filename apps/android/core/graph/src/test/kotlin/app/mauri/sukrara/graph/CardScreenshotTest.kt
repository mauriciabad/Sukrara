// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Bitmap
import android.graphics.Canvas
import app.mauri.sukrara.core.model.CardStatus
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.graph.stats.GlucosePoint
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/**
 * Roborazzi goldens of the card (synthetic readings only): one phone-width card per scene, light + dark, default +
 * largest font (the card scales its text with the box, so "largest font" is the taller box a surface gives it).
 * `recordRoborazziDebug` writes src/test/snapshots; `verifyRoborazziDebug` compares.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class CardScreenshotTest {
    private val d = 3f
    private val savedZone = TimeZone.getDefault()
    private val savedLocale = Locale.getDefault()

    @Before
    fun setUp() {
        // goldens independent of the machine: UTC, US English (12-hour clock)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        RoundedFont.init(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    private val style = CardStyle(rings = listOf(RingSpec(86_400), RingSpec(7 * 86_400L)), last = LastPlace.NEAR_VALUE, sensorTime = true)

    private fun scenes(): List<Pair<String, CardModel>> {
        val w = Scenes.words()
        val meal =
            listOf(
                Scenes.treatment("i1", TreatmentKind.RapidInsulin, 4.5, 40 * 60),
                Scenes.treatment("c1", TreatmentKind.Carbs, 45.0, 38 * 60),
            )
        return listOf(
            "in-range" to Scenes.model(Scenes.day(125), w, treatments = meal),
            "low" to Scenes.model(Scenes.day(70, rate = -2.2, swing = 12), w, status = CardStatus.Needed(lowSide = true)),
            "high" to Scenes.model(Scenes.day(230, rate = 1.4), w, status = CardStatus.Paused(false, Scenes.NOW + 45 * 60)),
            "stale-6min" to Scenes.model(Scenes.day(140, ageS = 6 * 60), w),
            "stale-25min" to Scenes.model(Scenes.day(140, ageS = 25 * 60), w),
            "no-data" to Scenes.model(emptyList<GlucosePoint>(), w),
            "warm-up" to
                Scenes.model(Scenes.day(140, ageS = 3 * 3600), w, sensor = SensorInfo(Scenes.NOW - 18 * 60, Scenes.NOW + 15 * 86_400)),
            "sensor-ended" to
                Scenes.model(Scenes.day(140, ageS = 30 * 60), w, sensor = SensorInfo(Scenes.NOW - 15 * 86_400, Scenes.NOW - 20 * 60)),
        )
    }

    private fun shoot(
        name: String,
        model: CardModel,
        dark: Boolean,
        large: Boolean,
    ) {
        val hDp = if (large) 300f else 240f
        val pal = GraphPalette.of(dark)
        val card = GlucoseCard.image(379f, hDp, d, model, style, pal, CardSurface.TEST)
        // the card is transparent (each surface paints its own background): shot on the palette's background
        val bmp = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(pal.bg)
            drawBitmap(card, 0f, 0f, null)
        }
        bmp.captureRoboImage("src/test/snapshots/card-$name-${if (dark) "dark" else "light"}${if (large) "-largest" else ""}.png")
    }

    @Test
    fun cards() {
        for ((name, model) in scenes()) {
            for (dark in listOf(false, true)) for (large in listOf(false, true)) shoot(name, model, dark, large)
        }
    }
}
