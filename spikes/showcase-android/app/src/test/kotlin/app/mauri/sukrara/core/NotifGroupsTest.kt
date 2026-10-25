// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import app.mauri.sukrara.alerts.AlertNotifier
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.owner.PendingRequest
import app.mauri.sukrara.pen.PenNotifier
import app.mauri.sukrara.sensorend.SensorEnd
import app.mauri.sukrara.status.GlucoseNotification
import app.mauri.sukrara.wearer.Live
import app.mauri.sukrara.wearer.WearerStatus
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Round 53: on Android 16+ a glucose alert was force-grouped with *Sensor ready* (AOSP GroupHelper: 2 ungrouped
 * notifications in one shade section, or 3 groups with 1–2 children each, are bundled). Pins what keeps it apart
 * (core/NotifGroups): every channel's importance (= its section), every notification's group, a summary per group
 * that never alerts and lives as long as its children, and that nothing posts around NotifGroups.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotifGroupsTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private val now get() = System.currentTimeMillis() / 1000

    @Before fun fresh() {
        nm.cancelAll()
        NotifGroups.resetForTest()
        Live.status.value = WearerStatus()
        SensorEnd.resetForTest(StandardTestDispatcher())
        ctx.getSharedPreferences("role", Context.MODE_PRIVATE).edit().putString("role", "owner").commit()
    }

    /** Every channel and its importance: ≥ DEFAULT = the alerting section, below = the silent one. */
    private val expected: Map<String, Int> = mapOf(
        GlucoseNotification.CHANNEL to NotificationManager.IMPORTANCE_LOW,   // the card: alone in the silent section
        Notifications.REQUESTS to NotificationManager.IMPORTANCE_HIGH,
        Notifications.STATUS to NotificationManager.IMPORTANCE_DEFAULT,
        SensorEnd.CH_REMIND to NotificationManager.IMPORTANCE_DEFAULT,
        SensorEnd.CH_COUNTDOWN to NotificationManager.IMPORTANCE_DEFAULT,
        PenNotifier.READS to NotificationManager.IMPORTANCE_DEFAULT,
        PenNotifier.CHECK to NotificationManager.IMPORTANCE_DEFAULT,
    ) + Kind.ALL.flatMap { k -> listOf(true, false).map { AlertNotifier.channelId(k, it) to NotificationManager.IMPORTANCE_HIGH } }

    private fun allChannels() {
        Notifications.channels(ctx); AlertNotifier.channels(ctx); SensorEnd.channels(ctx); PenNotifier.channels(ctx)
        GlucoseNotification.createChannel(ctx)
    }

    @Test fun everyChannelIsPinned() {
        allChannels()
        assertEquals(expected, nm.notificationChannels.associate { it.id to it.importance })
    }

    private fun StatusBarNotification.summary() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
    private fun importance(s: StatusBarNotification) = nm.getNotificationChannel(s.notification.channelId).importance

    /**
     * Everything at once (two ringing alerts, a muted-style silent one, both sensor notifications, a pen read, an
     * access request, a follower status): what Android 16's GroupHelper needs to leave it all alone.
     */
    @Test fun everyNotificationIsInItsGroup() {
        allChannels()
        val alerts = listOf(Kind.HIGH, Kind.RISING_FAST)
        AlertNotifier.post(ctx, Kind.HIGH, 220, 0.0, 180.0, now, null, alarm = false, treated = 0)
        AlertNotifier.post(ctx, Kind.RISING_FAST, 220, 3.0, 2.0, now, null, alarm = true, treated = 0)
        AlertNotifier.post(ctx, Kind.NO_DATA, null, null, 20.0, now, null, alarm = false, treated = 0, silent = true)
        // the warm-up countdown (ungrouped Live Update) at the start of a sensor
        val t0 = now
        SensorLife.update(ctx, SensorLife.fromStart("G1", t0 - 50 * 60, 0))
        SensorEnd.sync(ctx, t0)
        assertNotNull(nm.activeNotifications.firstOrNull { it.id == SensorEnd.ID_WARMUP })
        Features.init(ctx)
        Features.update { it.copy(penNotify = true) }
        PenNotifier.read(ctx, "Pen read", "8.5u rapid", null, canUndo = false)
        Features.update { it.copy(penNotify = false) }
        Notifications.request(ctx, PendingRequest("f1", "Mom", "tok", "1234", t0))
        Notifications.post(ctx, Notifications.ID_STATUS, Notifications.STATUS, "Status", "text")

        val all = nm.activeNotifications.toList()
        val groups = NotifGroups.Group.entries.associateBy { it.key }
        for (s in all) {
            val g = groups[s.notification.group]
            when {
                s.summary() -> {
                    assertNotNull("summary ${s.id} in a known group", g)
                    assertEquals(g!!.summaryId, s.id)
                    assertEquals("a summary never alerts", Notification.GROUP_ALERT_CHILDREN, s.notification.groupAlertBehavior)
                }
                s.id in NotifGroups.UNGROUPED -> assertNull("${s.id} stays ungrouped", s.notification.group)
                else -> {
                    assertNotNull("${s.id} (${s.notification.channelId}) is in Sukrara's groups", g)
                    assertTrue("${s.id}'s group has its summary", all.any { it.id == g!!.summaryId })
                    // alerts, and only alerts, in the alert group
                    assertEquals(s.notification.channelId.startsWith("alert_"), g == NotifGroups.Group.ALERTS)
                }
            }
        }
        // a ringing alert alerts as a child; the re-shown / muted one defers to the summary, which never alerts
        for (k in alerts) assertEquals(Notification.GROUP_ALERT_ALL, all.single { it.id == AlertNotifier.notificationId(k) }.notification.groupAlertBehavior)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, all.single { it.id == AlertNotifier.notificationId(Kind.NO_DATA) }.notification.groupAlertBehavior)
        // GroupHelper's thresholds: config_autoGroupAtCount = 2 ungrouped per section, AUTOGROUP_SPARSE_GROUPS_AT_COUNT = 3
        // groups with fewer than 3 children per section
        val alerting = all.filter { importance(it) >= NotificationManager.IMPORTANCE_DEFAULT }
        assertTrue("at most one ungrouped in the alerting section", alerting.count { it.notification.group == null } < 2)
        assertTrue("fewer than 3 groups", alerting.mapNotNull { it.notification.group }.distinct().size < 3)
        assertTrue("nothing else in the silent section", all.none { importance(it) < NotificationManager.IMPORTANCE_DEFAULT })
    }

    private fun shown(id: Int) = nm.activeNotifications.any { it.id == id }

    @Test fun theSummaryLivesAsLongAsItsChildren() {
        allChannels()
        val alerts = NotifGroups.Group.ALERTS.summaryId
        val info = NotifGroups.Group.INFO.summaryId
        AlertNotifier.post(ctx, Kind.LOW, 65, 0.0, 70.0, now, null, alarm = true, treated = 0)
        assertTrue(shown(alerts))
        AlertNotifier.cancel(ctx, Kind.LOW)
        assertTrue("gone with its last child", !shown(alerts))

        Notifications.post(ctx, Notifications.ID_STATUS, Notifications.STATUS, "Status", "text")
        Notifications.request(ctx, PendingRequest("f1", "Mom", "tok", "1234", now))
        Notifications.cancel(ctx, Notifications.ID_STATUS)
        assertTrue("a child is left", shown(info))
        // a timeout (Android cancels the child and sends its delete intent)
        val req = nm.activeNotifications.single { it.id == Notifications.ID_REQUEST }.notification
        assertNotNull("the delete intent tidies", req.deleteIntent)
        nm.cancel(Notifications.ID_REQUEST)
        NotifGroups.TidyReceiver().onReceive(ctx, Intent())
        assertTrue(!shown(info))

        // a child left without its summary gets it back
        AlertNotifier.post(ctx, Kind.HIGH, 220, 0.0, 180.0, now, null, alarm = false, treated = 0)
        nm.cancel(alerts)
        NotifGroups.tidy(ctx)
        assertTrue(shown(alerts))
    }

    /**
     * No notification around NotifGroups: every `NotificationManager.notify` in the app is in NotifGroups (grouped,
     * or one of its [NotifGroups.UNGROUPED]) or the glucose card's own poster; the foreground services start with the card.
     */
    @Test fun everyPostGoesThroughNotifGroups() {
        val src = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val notify = Regex("""(?<!PenReadActivity)\.notify\(""")
        val allowed = setOf("core/NotifGroups.kt", "status/GlucoseNotification.kt")
        val offenders = src.walk().filter { it.extension == "kt" }.flatMap { f ->
            val rel = f.relativeTo(File(src, "app/mauri/sukrara")).path
            f.readLines().withIndex().filter { (_, l) -> notify.containsMatchIn(l) && rel !in allowed }.map { "$rel:${it.index + 1}" } +
                f.readLines().withIndex().filter { (_, l) -> "startForeground(" in l && "GlucoseNotification.ID" !in l && "fun " !in l }
                    .map { "$rel:${it.index + 1} (startForeground)" }
        }.toList()
        assertEquals("post through core/NotifGroups (with a group) or add the id to NotifGroups.UNGROUPED", emptyList<String>(), offenders)
    }
}
