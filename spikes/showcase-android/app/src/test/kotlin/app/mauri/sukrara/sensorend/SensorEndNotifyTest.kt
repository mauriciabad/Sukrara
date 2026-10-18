// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensorend

import android.app.NotificationManager
import android.content.Context
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import org.junit.Assert.assertNotNull
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
}
