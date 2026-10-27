// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.monitor.R

/**
 * Two channels per alert kind, one per volume, so the person can set each kind's sound, vibration and Do Not
 * Disturb behaviour in the system settings. A channel's audio can't change after it exists, so the per-kind *alarm*
 * setting picks the channel:
 * - `alert_alarm.<kind>`: `USAGE_ALARM` + `CATEGORY_ALARM` — alarm volume, passes "Alarms" in Do Not Disturb, exempt
 *   from notification cooldowns; `setBypassDnd(true)` takes effect for Priority mode when the person granted DND access.
 * - `alert.<kind>`: `USAGE_NOTIFICATION_EVENT` + `CATEGORY_REMINDER` — notification volume, exempt from the cross-app
 *   "avalanche" cooldown after unlocking (PoC docs/15).
 * Both IMPORTANCE_HIGH (heads-up), sound `content://<appId>.alertsound/<kind>` ([AlertSoundProvider] picks the file at
 * play time), the kind's vibration. Ids never change: renaming a channel resets the person's choices.
 */
object AlertChannels {
    const val GROUP = "alerts"
    const val GROUP_ALARM = "alerts_alarm"

    fun id(kind: AlertKind, alarm: Boolean) = if (alarm) "alert_alarm.${kind.wire}" else "alert.${kind.wire}"

    val ALL: List<String> = AlertKind.entries.flatMap { listOf(id(it, false), id(it, true)) }

    fun audioAttributes(alarm: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun soundUri(ctx: Context, kind: AlertKind): Uri = Uri.parse("content://${ctx.packageName}.alertsound/${kind.wire}")

    /** Creates (or renames, in the current language) the groups and channels; idempotent. */
    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, ctx.getString(R.string.channel_group_alerts)))
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ALARM, ctx.getString(R.string.channel_group_alerts_alarm)))
        for (kind in AlertKind.entries) for (alarm in listOf(false, true)) {
            val label = AlertNotifier.kindLabel(ctx, kind)
            nm.createNotificationChannel(
                NotificationChannel(id(kind, alarm), label, NotificationManager.IMPORTANCE_HIGH).apply {
                    group = if (alarm) GROUP_ALARM else GROUP
                    description = ctx.getString(
                        if (alarm) R.string.channel_alert_description_alarm else R.string.channel_alert_description,
                        label,
                    )
                    setSound(soundUri(ctx, kind), audioAttributes(alarm))
                    enableVibration(true)
                    vibrationEffect = AlertHaptics.effect(ctx, kind)
                    setBypassDnd(alarm)
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }
}
