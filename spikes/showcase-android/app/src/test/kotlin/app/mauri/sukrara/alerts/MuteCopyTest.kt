// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.content.Context
import android.content.res.Configuration
import app.mauri.sukrara.core.Words
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * [MuteCopy] against THE shared cases (spikes/alerts/copy.json; the Mac runs them in `-alerts-selftest`), in English
 * and Spanish from the app's own resources (values/ and values-es/): every mute / remind-less state line, the muted
 * notification's title and text, the card's pill and the Mute alerts choices (round f31).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MuteCopyTest {
    private val fixture: JSONObject = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "spikes/alerts/copy.json") }.first { it.isFile }.readText().let(::JSONObject)

    private fun ctx(lang: String): Context = RuntimeEnvironment.getApplication().let { app ->
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) })
    }

    /** The line [MuteCopy] builds for case [c] in [ctx]'s language. */
    private fun line(c: JSONObject, ctx: Context): String {
        val w = Words.of(ctx.resources)
        val side = c.optString("side")
        return when (val st = c.getString("state")) {
            "side_muted" -> MuteCopy.sideMuted(ctx, side, c.getLong("leftMin"), w)
            "side_after" -> MuteCopy.sideAfter(ctx, side, c.getString("treatment"), c.getLong("leftMin"), w)
            "side_falling" -> MuteCopy.sideFalling(ctx, side)
            "side_less" -> MuteCopy.sideLess(ctx, side, c.getInt("intervalMin"), w)
            "all_timed" -> MuteCopy.allTimed(ctx, c.getLong("leftMin"), w)
            "all_forever" -> MuteCopy.allForever(ctx)
            "notif_title" -> MuteCopy.notifTitle(ctx, side)
            "notif_text" -> MuteCopy.notifText(ctx, c.getString("until"))
            "pill" -> MuteCopy.pill(c.getLong("leftMin"), w)
            "pill_forever" -> MuteCopy.pillForever(w)
            "choice" -> MuteCopy.choice(ctx, c.getInt("min"), w)
            "choice_forever" -> MuteCopy.choiceForever(ctx)
            else -> error("unknown state $st")
        }
    }

    private fun check(lang: String) {
        val cases = fixture.getJSONArray("cases")
        val ctx = ctx(lang)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals("$lang ${c.getString("state")} #$i", c.getString(lang), line(c, ctx))
        }
    }

    @Test fun english() = check("en")

    @Test fun spanish() = check("es")

    /**
     * Round f33: Spanish agrees with the time left ("queda 1 h", "quedan 2 h", "quedan 1 h 5 min"): the mute lines, the
     * badge's pill and what the screen reader says about it (the widgets, the app's card), the sensor's time left.
     */
    @Test fun spanishTimeLeftAgrees() {
        val es = ctx("es")
        val w = Words.of(es.resources)
        assertEquals("queda 1 min", MuteCopy.left(es, 1, w))
        assertEquals("queda 1 h", MuteCopy.left(es, 60, w))
        assertEquals("quedan 2 h", MuteCopy.left(es, 120, w))
        assertEquals("quedan 1 h 1 min", MuteCopy.left(es, 61, w))
        assertEquals("quedan 12 min", MuteCopy.left(es, 12, w))
        assertEquals("1 h left", MuteCopy.left(ctx("en"), 60, Words.of(ctx("en").resources)))
        val now = 1_800_000_000L
        val prev = Words.loader
        val locale = Locale.getDefault()
        Words.loader = { w }
        Locale.setDefault(Locale.forLanguageTag("es"))
        try {
            val badge = app.mauri.sukrara.graph.StatusBadge
            // the screen reader: the mute lines, never "quedan 1 h"
            assertEquals("Alertas silenciadas · queda 1 h", badge.describe(es, AlertStatus.Muted(now + 3600), now))
            assertEquals("Alertas de glucosa baja silenciadas · queda 1 min", badge.describe(es, AlertStatus.Paused(Kind.VERY_LOW, now + 30), now))
            assertEquals("Alertas de glucosa baja silenciadas tras los hidratos · quedan 12 min",
                badge.describe(es, AlertStatus.Taken(Kind.LOW, app.mauri.sukrara.treatments.Treatment(id = "c", k = "carbs", a = 15.0, t = now - 180, u = 0, o = "phone"), now + 720), now))
            assertEquals("Alertas silenciadas hasta que lo desactives", badge.describe(es, AlertStatus.Paused(Kind.LOW, AlertEngine.NEVER), now))
            // the pill: "queda 1 h", "quedan 45 min", "quedan 1 h 5 min"
            assertEquals("queda 1 h", badge.look(AlertStatus.Paused(Kind.LOW, now + 3600), now)?.text)
            assertEquals("quedan 45 min", badge.look(AlertStatus.Paused(Kind.LOW, now + 45 * 60), now)?.text)
            assertEquals("quedan 1 h 5 min", badge.look(AlertStatus.Paused(Kind.LOW, now + 65 * 60), now)?.text)
            // the sensor's time left
            val sensor = app.mauri.sukrara.graph.SensorBadge
            fun sensorSays(leftS: Long) = sensor.describe(app.mauri.sukrara.graph.SensorBadge.Look(
                app.mauri.sukrara.core.SensorLife.label(now + leftS, now, w), 0.5f, false, end = now + leftS), now, w)
            assertEquals("Sensor: queda 1 d", sensorSays(86_400 + 3600))
            assertEquals("Sensor: quedan 13 d", sensorSays(13 * 86_400L))
        } finally {
            Locale.setDefault(locale)
            Words.loader = prev
        }
    }

    /** Every side kind names its side: very low is "Low", extremely high "High". */
    @Test fun sidesUseTheirMainName() {
        val en = ctx("en")
        assertEquals("Low alerts muted", MuteCopy.notifTitle(en, Kind.VERY_LOW))
        assertEquals("High alerts muted", MuteCopy.notifTitle(en, Kind.EXTREMELY_HIGH))
        assertEquals("Falling fast alerts muted", MuteCopy.notifTitle(en, Kind.FALLING_FAST))
        assertEquals("Alertas de glucosa alta silenciadas", MuteCopy.notifTitle(ctx("es"), Kind.VERY_HIGH))
    }

    /** The countdown: partial minutes round up, the next change is the minute boundary counted from the end. */
    @Test fun countdownTicksOnTheMinuteFromTheEnd() {
        val until = 10_000L
        assertEquals(72, MuteCopy.leftMin(until, until - 72 * 60))
        assertEquals(72, MuteCopy.leftMin(until, until - 71 * 60 - 1))
        assertEquals(1, MuteCopy.leftMin(until, until - 1))
        assertEquals(until - 71 * 60, MuteCopy.nextChangeAt(until, until - 72 * 60))
        assertEquals(until - 71 * 60, MuteCopy.nextChangeAt(until, until - 71 * 60 - 30))
        assertEquals(until, MuteCopy.nextChangeAt(until, until - 30))
        assertNull(MuteCopy.nextChangeAt(until, until))
        assertNull(MuteCopy.nextChangeAt(AlertEngine.NEVER, 0))
        assertNull(MuteCopy.nextChangeAt(0, 100))
        // the widgets' and lock screen's redraw alarm: the mute's next minute, or the reading's freshness step if sooner
        val now = until - 72 * 60
        assertEquals(until - 71 * 60, app.mauri.sukrara.status.FreshnessTicker.next(now - 10, now, listOf(until, 0L)))
        val step = app.mauri.sukrara.graph.Freshness.nextChangeAt(now - 85, now)!!
        assertTrue(step < until - 71 * 60)
        assertEquals(step, app.mauri.sukrara.status.FreshnessTicker.next(now - 85, now, listOf(until)))
        assertEquals(app.mauri.sukrara.graph.Freshness.nextChangeAt(now - 10, now), app.mauri.sukrara.status.FreshnessTicker.next(now - 10, now, listOf(AlertEngine.NEVER)))
    }
}
