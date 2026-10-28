// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import app.mauri.sukrara.monitor.R

/**
 * Glucose alerts are never bundled with other notifications (0.11.53 lesson). Android 16+ force-groups an app's
 * notifications per shade section (AOSP `GroupHelper`): ≥ 2 *ungrouped* notifications go into one system bundle, and
 * ≥ 3 *sparse* app groups (1–2 children each) are merged too. So every alerting notification of Sukrara is in one of
 * two app groups, each with its own summary: [Group.ALERTS] (every alert and muted card; two alerts at once still
 * each ring and show their own title, the summary never alerts: `GROUP_ALERT_CHILDREN`) and [Group.INFO] (sensor end,
 * status). One group per alert would be sparse groups and get merged with anything (measured on API 36/37).
 * The ongoing glucose notification stays ungrouped (alone in the silent section; a promoted Live Update can't be in
 * a group).
 */
object NotificationGroups {
    enum class Group(val key: String, val summaryId: Int, private val nameRes: Int) {
        ALERTS("app.mauri.sukrara.ALERTS", NotificationIds.ALERTS_SUMMARY, R.string.monitor_group_alerts),
        INFO("app.mauri.sukrara.INFO", NotificationIds.INFO_SUMMARY, R.string.notification_group_info),
        ;

        fun name(ctx: Context): String = ctx.getString(nameRes)
    }

    /** Posts [n] (built with `setGroup(group.key)`) after its group's summary. */
    fun post(ctx: Context, id: Int, n: Notification, group: Group, channelId: String) {
        check(n.group == group.key) { "notification $id is not in ${group.key}" }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(group.summaryId, summary(ctx, group, channelId, n.smallIcon.resId))
        nm.notify(id, n)
    }

    /** Cancels [id], and its group's summary once no child is left (a summary alone counts as ungrouped). */
    fun cancel(ctx: Context, id: Int, group: Group) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(id)
        tidy(ctx, group, except = id)
    }

    /** Removes [group]'s summary when it has no child (also after a swipe: the children's delete intent calls this). */
    fun tidy(ctx: Context, group: Group, except: Int? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val children = nm.activeNotifications.count { it.notification.group == group.key && it.id != group.summaryId && it.id != except }
        if (children == 0) nm.cancel(group.summaryId)
    }

    private fun summary(ctx: Context, group: Group, channelId: String, icon: Int): Notification =
        Notification
            .Builder(ctx, channelId)
            .setSmallIcon(icon)
            .setContentTitle(group.name(ctx))
            .setGroup(group.key)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .build()
}
