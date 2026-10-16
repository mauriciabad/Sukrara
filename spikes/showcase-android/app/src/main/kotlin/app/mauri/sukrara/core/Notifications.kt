// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.mauri.sukrara.ui.MainActivity

object Notifications {
    const val SERVICE = "service"
    const val REQUESTS = "requests"
    const val ALARMS = "alarms"
    const val STATUS = "status"

    const val ID_SERVICE = 1
    const val ID_REQUEST = 2
    const val ID_HIGH_LOW = 3
    const val ID_NO_DATA = 4
    const val ID_STATUS = 5

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SERVICE, "Running in background", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(REQUESTS, "Access requests", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(ALARMS, "Glucose alarms", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(STATUS, "Sharing status", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(ctx: Context) =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)

    fun build(ctx: Context, channel: String, title: String, text: String, ongoing: Boolean = false): Notification =
        Notification.Builder(ctx, channel)
            .setSmallIcon(if (channel == ALARMS) android.R.drawable.stat_sys_warning else app.mauri.sukrara.R.drawable.ic_stat_sukrara)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open(ctx))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setAutoCancel(!ongoing)
            .build()

    fun post(ctx: Context, id: Int, channel: String, title: String, text: String, ongoing: Boolean = false) {
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).notify(id, build(ctx, channel, title, text, ongoing))
        }
    }

    fun cancel(ctx: Context, id: Int) = ctx.getSystemService(NotificationManager::class.java).cancel(id)
}
