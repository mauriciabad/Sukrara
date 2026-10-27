// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.app.NotificationChannel
import android.app.Notification
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.net.toUri
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.monitor.notify.Channels

/**
 * Two channels per alert kind, one per volume, so the person can set each kind's sound, vibration and Do Not
 * Disturb behaviour in the system settings. A channel's audio can't change after it exists, so the per-kind *alarm*
 * setting picks the channel:
 * - `alert_alarm.<kind>`: `USAGE_ALARM` + `CATEGORY_ALARM` — alarm volume, passes "Alarms" in Do Not Disturb, exempt
 *   from notification cooldowns; `setBypassDnd(true)` takes effect for Priority mode when the person granted DND access.
 * - `alert.<kind>`: `USAGE_NOTIFICATION_EVENT` + `CATEGORY_REMINDER` — notification volume, exempt from the cross-app
 *   "avalanche" cooldown after unlocking (PoC docs/15).
 * Both IMPORTANCE_HIGH (heads-up; ids, names and groups in [Channels]), sound `content://<appId>.alertsound/<kind>` ([AlertSoundProvider] picks the file at
 * play time), the kind's vibration. Ids never change: renaming a channel resets the person's choices.
 */
object AlertChannels {
    fun id(kind: AlertKind, alarm: Boolean) = if (alarm) Channels.alarm(kind.wire) else Channels.alert(kind.wire)

    val ALL: List<String> = AlertKind.entries.flatMap { listOf(id(it, false), id(it, true)) }

    fun audioAttributes(alarm: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun soundUri(ctx: Context, kind: AlertKind): Uri = "content://${ctx.packageName}.alertsound/${kind.wire}".toUri()

    /** The alert setup of a channel [Channels] creates (sound, usage, vibration, Do Not Disturb for alarms). */
    fun configure(channel: NotificationChannel, ctx: Context, kind: AlertKind, alarm: Boolean) = channel.apply {
        setSound(soundUri(ctx, kind), audioAttributes(alarm))
        enableVibration(true)
        vibrationEffect = AlertHaptics.effect(ctx, kind)
        setBypassDnd(alarm)
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }

    /** Creates the app's channels (chunk 10's registry, idempotent), the alert ones with [configure]. */
    fun ensure(ctx: Context) = Channels.create(ctx)
}
