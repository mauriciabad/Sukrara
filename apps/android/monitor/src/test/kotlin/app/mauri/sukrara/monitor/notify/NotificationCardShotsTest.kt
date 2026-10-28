// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.CardStatus
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.graph.CardWords
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.cardModelOf
import app.mauri.sukrara.graph.notificationCardStyle
import app.mauri.sukrara.graph.stats.toPoint
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Roborazzi goldens of the glucose notification's custom card ([NotificationCard.renderNotificationArt]: header,
 * collapsed header and chart stacked as the shade shows them), synthetic readings only: light + dark × fresh, struck
 * (8 min old), with the status pill. The Goldens workflow (CI, Linux) records src/test/snapshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class NotificationCardShotsTest {
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

    /** A synthetic day ending [age] before now (every 5 min, a slow wave around 140), the last value [last]. */
    private fun frame(
        age: Duration,
        dark: Boolean,
        last: Int? = null,
        status: CardStatus = CardStatus.None,
        storedDays: Long = 0,
    ): CardFrame {
        val end = now.minus(age)

        fun wave(i: Int) = (140 + 60 * sin(2 * PI * i / 96.0) + 8 * sin(2 * PI * i / 11.0)).roundToInt()
        val readings =
            (288 downTo 0).map { i -> Reading(end.minusSeconds(i * 300L), if (i == 0 && last != null) last else wave(i), 0.6, SYNTH) }
        // the stored days before the live window: high all along (250), every 5 min
        val older =
            (storedDays * 288 downTo 1).map { i -> Reading(readings.first().t.minusSeconds(i * 300L), 250, null, SYNTH) }
        val g =
            GlucoseState.Initial.copy(
                role = Role.Wearer,
                latest = readings.last(),
                recent = readings,
                sensor = SensorState.Active(now.plus(Duration.ofDays(6))),
                alerts = AlertUiState(status = status),
            )
        return CardFrame(g, emptyList(), GraphConfig(), dark, older)
    }

    private fun shoot(
        frame: CardFrame,
        name: String,
    ) {
        val model = cardModelOf(frame.state, frame.treatments, now, CardWords.of(context.resources), frame.slots(now))
        val latest = frame.state.latest!!
        val look = Freshness.look(latest.mgdl, latest.t.epochSecond, latest.rateMgdlPerMin, now.epochSecond)
        val bg = NotificationCard.background(look, latest.mgdl, frame.config)
        val art = NotificationCard.renderNotificationArt(context, model, frame.config, bg, frame.dark, 300f)
        val gap = 8
        val parts = listOfNotNull(art.header, art.collapsed, art.graph)
        val out = Bitmap.createBitmap(parts.maxOf { it.width }, parts.sumOf { it.height } + gap * (parts.size + 1), Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(if (frame.dark) Color.rgb(0x1B, 0x1B, 0x1F) else Color.rgb(0xF4, 0xF3, 0xF8))
            var y = gap.toFloat()
            for ((i, p) in parts.withIndex()) {
                // the header pictures sit on the card's range colour, the chart on the theme's surface
                if (i < 2) drawRect(0f, y, p.width.toFloat(), y + p.height, android.graphics.Paint().apply { color = bg })
                drawBitmap(p, 0f, y, null)
                y += p.height + gap
            }
        }
        out.captureRoboImage("src/test/snapshots/notification-card-$name.png")
    }

    @Test
    fun cards() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            shoot(frame(Duration.ofMinutes(1), dark), "fresh-$theme")
            shoot(frame(Duration.ofMinutes(8), dark), "struck-$theme")
            shoot(frame(Duration.ofMinutes(1), dark, last = 62, status = CardStatus.Needed(lowSide = true)), "pill-$theme")
        }
    }

    @Test
    fun `the slots count the stored days like home, not only the live 24 h`() {
        fun texts(f: CardFrame) =
            GlucoseCard.slotTexts(
                cardModelOf(f.state, f.treatments, now, CardWords.of(context.resources), f.slots(now)),
                notificationCardStyle(f.config),
                3,
            )
        val live = texts(frame(Duration.ofMinutes(1), false))
        val week = texts(frame(Duration.ofMinutes(1), false, storedDays = 6))
        // the default two slots: time in range 1 d and 7 d; a 7-day window needs the stored days (≥ 25 % covered)
        assertEquals(1, live.size)
        assertEquals(2, week.size)
        assertEquals(live[0], week[0])

        fun pct(s: Pair<String, String>) = s.first.removeSuffix("%").toInt()
        assertTrue("7 days of mostly high read lower than the last day: $week", pct(week[1]) < pct(week[0]))
    }

    private companion object {
        val SYNTH = SourceId("synthetic")
    }
}
