// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import app.mauri.sukrara.R
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
    const val STATUS = "status"

    const val ID_SERVICE = 1
    const val ID_REQUEST = 2
    const val ID_STATUS = 5

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SERVICE, ctx.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(REQUESTS, ctx.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_HIGH))
        nm.deleteNotificationChannel("alarms")   // replaced by one channel per alert kind (alerts/AlertNotifier)
        nm.createNotificationChannel(NotificationChannel(STATUS, ctx.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(ctx: Context) =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)

    fun build(ctx: Context, channel: String, title: String, text: String, ongoing: Boolean = false): Notification =
        Notification.Builder(ctx, channel)
            .setSmallIcon(app.mauri.sukrara.R.drawable.ic_stat_sukrara)
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

    /**
     * An access request: "Mom wants to see your glucose · Asked with your invite for Mom · 14:02". A link request gets
     * Deny / Allow actions (Allow needs an unlocked phone: setAuthenticationRequired). A nearby one only opens the app,
     * where the wearer types the code shown on the follower's phone.
     */
    fun request(ctx: Context, p: app.mauri.sukrara.owner.PendingRequest) {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date((p.inviteAt.takeIf { it > 0 } ?: p.at) * 1000))
        val text = when {
            p.others > 0 -> ctx.getString(R.string.request_notif_others)
            p.nearby -> ctx.getString(R.string.request_notif_nearby)
            p.label.isNotBlank() -> ctx.getString(R.string.request_notif_for, p.label, time)
            else -> ctx.getString(R.string.request_notif_from, time)
        }
        fun action(what: String, label: String, auth: Boolean) = Notification.Action.Builder(null, label,
            PendingIntent.getBroadcast(ctx, what.hashCode(), Intent(ctx, app.mauri.sukrara.owner.RequestActionReceiver::class.java)
                .setAction(what).putExtra("f", p.f), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAuthenticationRequired(auth).build()
        val b = Notification.Builder(ctx, REQUESTS)
            .setSmallIcon(app.mauri.sukrara.R.drawable.ic_stat_sukrara)
            .setContentTitle(ctx.getString(R.string.request_notif_title, p.name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open(ctx))
            .setCategory(Notification.CATEGORY_SOCIAL)
            .setAutoCancel(true)
        p.avatar?.let { b.setLargeIcon(android.graphics.drawable.Icon.createWithBitmap(it.render(p.name, 192).circle())) }
        if (!p.nearby && p.others == 0) b.addAction(action(ACTION_DENY, ctx.getString(R.string.approve_deny), false)).addAction(action(ACTION_ALLOW, ctx.getString(R.string.approve_allow), true))
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_REQUEST, b.build()) }
    }

    const val ACTION_ALLOW = "app.mauri.sukrara.ALLOW_REQUEST"
    const val ACTION_DENY = "app.mauri.sukrara.DENY_REQUEST"

    private fun android.graphics.Bitmap.circle(): android.graphics.Bitmap {
        val out = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(this, 0f, 0f, paint)
        return out
    }
}
