// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.ui.setPenNotify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * *Notification after each read* (round 52, `penNotify`): off by default, then [PenNotifier.read] posts nothing;
 * on, it posts the *Pen reads* notification; switching it off takes the shown one away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PenNotifyTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    private fun posted() = nm.activeNotifications.count { it.notification.channelId == PenNotifier.READS }

    @Test fun offByDefaultPostsNothing() {
        assertFalse(FeatureState().penNotify)
        Features.init(ctx)
        Features.update { it.copy(penNotify = false) }
        PenNotifier.read(ctx, "Pen read", "8.5u rapid", null, canUndo = false)
        assertEquals(0, posted())
    }

    @Test fun onPostsAndSwitchingOffRemovesIt() {
        Features.init(ctx)
        Features.update { it.copy(penNotify = true) }
        PenNotifier.read(ctx, "Pen read", "8.5u rapid", null, canUndo = false)
        // the read and its own group's summary: never autogrouped with an alert (Android 16+); default importance,
        // silent (no sound, no vibration, so no heads-up)
        assertEquals(2, posted())
        val reads = nm.activeNotifications.filter { it.notification.channelId == PenNotifier.READS }
        assertEquals(1, reads.count { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 })
        assertEquals(1, reads.map { it.notification.group }.distinct().size)
        val ch = nm.getNotificationChannel(PenNotifier.READS)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, ch.importance)
        assertEquals(null, ch.sound)
        assertFalse(ch.shouldVibrate())
        assertEquals(null, nm.getNotificationChannel("pen_reads"))
        setPenNotify(ctx, false)
        assertEquals(0, posted())
        Features.update { it.copy(penNotify = false) }
    }
}
