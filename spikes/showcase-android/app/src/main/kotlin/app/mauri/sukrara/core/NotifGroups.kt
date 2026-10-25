// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Every notification Sukrara posts goes through here (or is the glucose card, `GlucoseNotification`), so Android 16+
 * never bundles a glucose alert with anything else (round 53).
 *
 * **Why.** Android 16 force-groups an app's notifications per shade section (AOSP `GroupHelper`, flag
 * `notification_force_grouping`; sections: *alerting* = channel importance ≥ DEFAULT, *silent* = below):
 *  1. **ungrouped**: once `config_autoGroupAtCount` (**2**) ungrouped notifications share a section, they all move
 *     into the system's `Aggregate_<Section>` group (a "2" bundle with an autogroup summary). A group child whose
 *     summary is missing, and a summary with no children (checked 3 s after the post), count as ungrouped.
 *  2. **sparse groups** (flag `notification_force_group_singletons`): once **3** app groups
 *     (`AUTOGROUP_SPARSE_GROUPS_AT_COUNT`) in one section have only 1–2 children each
 *     (`MIN_CHILD_COUNT_TO_AVOID_FORCE_GROUPING` = 3), their children are moved into the same aggregate group and
 *     the app's summaries removed.
 *  Not groupable at all: a colorized foreground-service notification (the glucose card with the chip off), calls,
 *  media. Measured before this round (API 36 + 37 emulators): *High glucose* in `g:Aggregate_AlertingSection` with
 *  *Sensor ready*.
 *
 * **What Sukrara does.** Two app groups, each with its own summary, so rule 1 never sees two ungrouped notifications
 * and rule 2 never sees three sparse groups:
 *  - [Group.ALERTS]: every glucose alert (all kinds, muted and re-shown ones too). Two alerts at once (high + rising
 *    fast) share this group: still each one heads-up with its own sound (children alert, the summary never does:
 *    `GROUP_ALERT_CHILDREN`), both titles readable in the collapsed group, never mixed with anything else.
 *  - [Group.INFO]: everything else in the alerting section (sensor reminders, *Sensor ready*, *Sensor ended*, pen
 *    reads, pen doses to check, access requests, a follower's status).
 *  - Ungrouped, on purpose: the glucose card (alone in the silent section; not groupable while colorized) and the
 *    sensor countdown Live Update (warm-up or end: one at a time, so never two ungrouped; a promoted notification
 *    stays out of a group so it keeps its Live Update look). [UNGROUPED] lists them; `NotifGroupsTest` pins it all.
 *
 * A summary lives exactly as long as its group has children: [post] posts it before the child, [cancel] and [tidy]
 * remove it once the last child is gone (System UI already dismisses it with the last child on a tap or a swipe; a
 * child's timeout reaches [TidyReceiver] through its delete intent, which Android sends on a timeout too).
 */
object NotifGroups {
    enum class Group(val key: String, val summaryId: Int) {
        ALERTS("app.mauri.sukrara.ALERTS", 119),
        INFO("app.mauri.sukrara.INFO", 19),
    }

    /** The glucose card and the sensor countdowns (warm-up / end, never both): the only ungrouped notifications. */
    val UNGROUPED = setOf(app.mauri.sukrara.status.GlucoseNotification.ID, app.mauri.sukrara.sensorend.SensorEnd.ID_COUNTDOWN,
        app.mauri.sukrara.sensorend.SensorEnd.ID_WARMUP)

    private const val TAG = "Sukrara.notifGroups"
    /** Children posted in the last moments: NotificationManager posts asynchronously, so they may not be active yet. */
    private val recent = java.util.concurrent.ConcurrentHashMap<Int, Pair<Group, Long>>()
    private const val RECENT_MS = 1_500L
    private val later by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "Sukrara.notifGroups").apply { isDaemon = true } }
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun group(key: String?): Group? = Group.entries.firstOrNull { it.key == key }

    /** Posts [n] (built with `setGroup(group.key)`) after its group's summary. */
    fun post(ctx: Context, id: Int, n: Notification, group: Group) {
        check(n.group == group.key) { "notification $id is not in ${group.key}" }
        if (n.deleteIntent == null) n.deleteIntent = tidyIntent(ctx)
        recent[id] = group to SystemClock.elapsedRealtime()
        runCatching {
            nm(ctx).notify(group.summaryId, summary(ctx, n, group))
            nm(ctx).notify(id, n)
        }.onFailure { Log.w(TAG, "post $id: $it") }
    }

    /** Posts one of the [UNGROUPED] notifications (the sensor countdowns; the glucose card posts itself). */
    fun postUngrouped(ctx: Context, id: Int, n: Notification) {
        check(id in UNGROUPED && n.group == null) { "notification $id must be in a group" }
        runCatching { nm(ctx).notify(id, n) }.onFailure { Log.w(TAG, "post $id: $it") }
    }

    /** Cancels [ids], and a summary whose group has no child left. */
    fun cancel(ctx: Context, vararg ids: Int) {
        val nm = nm(ctx)
        ids.forEach { recent.remove(it); runCatching { nm.cancel(it) } }
        tidy(ctx, ids.toSet())
        // NotificationManager cancels asynchronously too: look again once the queue has settled (not in the JVM
        // tests: Robolectric posts and cancels at once, and a late tidy would land in the next test)
        if (android.os.Build.FINGERPRINT != "robolectric")
            runCatching { later.schedule({ runCatching { tidy(ctx.applicationContext) } }, 2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTest() = recent.clear()

    /**
     * The summaries match the children: a summary without children goes (Android would show it alone and count it as
     * ungrouped), children without a summary get it back. [gone]: ids just cancelled that may still be listed.
     * [settled]: the list is current (a delete intent arrives after the removal), so a child posted a moment ago
     * that isn't listed is gone, not on its way.
     */
    fun tidy(ctx: Context, gone: Set<Int> = emptySet(), settled: Boolean = false) = runCatching {
        val nm = nm(ctx)
        val now = SystemClock.elapsedRealtime()
        recent.entries.removeIf { now - it.value.second > RECENT_MS }
        val active = nm.activeNotifications.toList()
        for (g in Group.entries) {
            val children = active.filter { it.notification.group == g.key && !it.isSummary() && it.id !in gone }
            // a child posted a moment ago that isn't listed yet keeps its summary
            val pending = !settled && recent.any { (id, v) -> v.first == g && id !in gone && active.none { it.id == id } }
            val summary = active.any { it.id == g.summaryId }
            when {
                children.isEmpty() && !pending && summary -> nm.cancel(g.summaryId)
                children.isNotEmpty() && !summary -> nm.notify(g.summaryId, summary(ctx, children.maxBy { it.postTime }.notification, g))
            }
        }
    }.onFailure { Log.w(TAG, "tidy: $it") }

    private fun android.service.notification.StatusBarNotification.isSummary() =
        notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    /**
     * The group's summary, from its newest child: same channel (same section, same Do Not Disturb result), category,
     * icon, colour and lock-screen visibility; it never alerts (`GROUP_ALERT_CHILDREN`: the children do). Shown only
     * where Android shows a summary (a collapsed group of 2+, a watch); a single child shows alone.
     */
    fun summary(ctx: Context, child: Notification, group: Group): Notification {
        val b = Notification.Builder(ctx, child.channelId)
            .setGroup(group.key).setGroupSummary(true).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setContentTitle(child.extras.getCharSequence(Notification.EXTRA_TITLE))
            .setContentText(child.extras.getCharSequence(Notification.EXTRA_TEXT))
            .setColor(child.color)
            .setCategory(child.category)
            .setVisibility(child.visibility)
            .setContentIntent(child.contentIntent)
            .setOnlyAlertOnce(true).setAutoCancel(true)
        child.smallIcon?.let { b.setSmallIcon(it) } ?: b.setSmallIcon(app.mauri.sukrara.R.drawable.ic_stat_sukrara)
        child.publicVersion?.let { b.setPublicVersion(it) }
        return b.build()
    }

    private fun tidyIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 0,
        Intent(ctx, TidyReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A grouped child was removed by Android (its timeout) or swiped: the summary goes with the last child. */
    class TidyReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { tidy(c, settled = true) }
    }
}
