// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensorend

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.wearer.Live
import app.mauri.sukrara.wearer.WearerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Android 16 without QPR2 (API 36.0) has no promoted notifications: `setRequestPromotedOngoing` and
 * `canPostPromotedNotifications` don't exist there (NoSuchMethodError crash-looped App.onCreate on the 36.0 image).
 * Robolectric's SDK 36 is 36.0, so posting both countdowns here must work without them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SensorEndNotifyTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private val now get() = System.currentTimeMillis() / 1000

    /** Live is process-wide: no reading left over from another test (it would end a warm-up). */
    @Before fun noReadings() { Live.status.value = WearerStatus() }

    private fun owner() = ctx.getSharedPreferences("role", Context.MODE_PRIVATE).edit().putString("role", "owner").commit()

    @Test fun endCountdownPostsOnApi36Zero() {
        owner()
        SensorLife.update(ctx, SensorInfo("T1", now - 14 * 86_400L, now + 4 * 3600L))
        SensorEnd.sync(ctx)
        assertNotNull(nm.activeNotifications.firstOrNull { it.id == SensorEnd.ID_COUNTDOWN })
        SensorEnd.state(ctx)   // the debug reply asks canPostPromotedNotifications too
    }

    @Test fun warmupCountdownPostsOnApi36Zero() {
        owner()
        SensorLife.update(ctx, SensorLife.fromStart("T2", now - 600, 0))
        SensorEnd.sync(ctx)
        assertNotNull(nm.activeNotifications.firstOrNull { it.id == SensorEnd.ID_WARMUP })
    }

    private fun warmupShown() = nm.activeNotifications.firstOrNull { it.id == SensorEnd.ID_WARMUP }?.notification
    private fun Notification.timer() = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)

    /**
     * The phone, 2026-10-18: a sensor started 20:08, readings from 21:08; at 21:14 the warm-up countdown was still up,
     * its timer below zero ("ready" had been shown, then an app update restarted the process, which re-posted the
     * countdown). Past the warm-up's end it shows "Almost ready" without a timer; a real reading (the direct source's
     * path: Live, not the debug flag) ends it, a restart doesn't bring it back, and a leftover is cancelled.
     */
    @Test fun pastTheWarmupEndNoTimerAndTheFirstReadingEndsIt() {
        owner()
        // a month ahead: a reading some other test's source still sends (wall-clock time) is before this sensor's start
        val t0 = now + 30 * 86_400L
        SensorLife.update(ctx, SensorLife.fromStart("T3", t0 - 50 * 60, 0))
        SensorEnd.sync(ctx, t0)
        assertTrue("the countdown has its timer", warmupShown()!!.timer())

        // 66 min in (6 min past the warm-up's end), no reading yet
        val t1 = t0 + 16 * 60
        SensorEnd.sync(ctx, t1)
        val waiting = warmupShown()!!
        assertFalse("no timer once the warm-up is over", waiting.timer())
        assertEquals(ctx.getString(app.mauri.sukrara.R.string.card_almost_ready), waiting.extras.getString(Notification.EXTRA_TITLE))

        // the first reading: the warm-up notification goes, "Sensor ready" is its own notification
        Live.reading(Reading(t1, 120, null, "libre3"))
        SensorEnd.sync(ctx, t1 + 5)
        assertNull(warmupShown())
        assertNotNull(nm.activeNotifications.firstOrNull { it.id == SensorEnd.ID_READY })

        // a restart (an app update): Live starts empty, the remembered first reading keeps the warm-up away
        Live.status.value = WearerStatus()
        SensorEnd.sync(ctx, t1 + 30)
        assertNull(warmupShown())

        // a stale countdown left behind is cancelled by the next re-plan, "ready" shown or not
        nm.notify(SensorEnd.ID_WARMUP, Notification.Builder(ctx, SensorEnd.CH_COUNTDOWN).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen((t1 - 360) * 1000).build())
        SensorEnd.sync(ctx, t1 + 60)
        assertNull(warmupShown())
    }
}
