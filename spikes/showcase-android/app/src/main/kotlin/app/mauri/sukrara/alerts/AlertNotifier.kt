// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import app.mauri.sukrara.R
import app.mauri.sukrara.core.Range
import app.mauri.sukrara.core.Trend
import app.mauri.sukrara.ui.MainActivity

/**
 * Two notification channels per alert kind, one per volume, so each kind's sound, vibration and Do Not Disturb
 * behaviour can be set in the system settings (developer.android.com/design/ui/mobile/guides/home-screen/notifications:
 * "Users can change these settings and decide which notification channels from your app can be intrusive or
 * visible"). A channel's audio attributes can't change after it's created, so the per-kind "Volume" setting
 * ([KindCfg.alarm]) picks the channel instead:
 *
 * - **Alarm** (default for lows): USAGE_ALARM + CATEGORY_ALARM. Alarm volume, passes "Alarms only" Do Not Disturb
 *   (ZenModeFiltering.isAlarm) and is exempt from every notification cooldown.
 * - **Notification** (default for the rest): USAGE_NOTIFICATION_EVENT + CATEGORY_REMINDER. Exempt from the cross-app
 *   "avalanche" cooldown that otherwise mutes every notification for a while after unlocking (measured on the
 *   emulator: a high posted then made no sound, docs/15); through Do Not Disturb only if the user allows reminders.
 */
object AlertNotifier {
    private const val GROUP = "alerts"
    private const val GROUP_ALARM = "alerts_alarm"
    private const val ID_BASE = 100

    fun channelId(kind: String, alarm: Boolean) = if (alarm) "alert_a_$kind" else "alert_n_$kind"

    fun audioAttributes(alarm: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun isLow(kind: String) = kind in Kind.LOW_SIDE

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ids = Kind.ALL.flatMap { listOf(channelId(it, false), channelId(it, true)) }.toSet()
        // channels of kinds and layouts that no longer exist (0.11.1 `alert_<kind>`, the renamed `urgent_low`)
        nm.notificationChannels.filter { it.id.startsWith("alert_") && it.id !in ids }.forEach { nm.deleteNotificationChannel(it.id) }
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, "Glucose alerts"))
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ALARM, "Glucose alerts at alarm volume"))
        for (k in Kind.ALL) for (alarm in listOf(false, true)) {
            nm.createNotificationChannel(NotificationChannel(channelId(k, alarm), Kind.label(k), NotificationManager.IMPORTANCE_HIGH).apply {
                group = if (alarm) GROUP_ALARM else GROUP
                description = DESCRIPTIONS.getValue(k) +
                    if (alarm) " Rings at alarm volume, like an alarm clock." else " Rings at notification volume."
                setSound(AlertSounds.uri(ctx, k), audioAttributes(alarm))
                enableVibration(true)
                vibrationEffect = AlertHaptics.effect(ctx, k)
                enableLights(true)
                lightColor = if (isLow(k)) Range.RED else Range.ORANGE
                setShowBadge(false)
            })
        }
    }

    private val DESCRIPTIONS = mapOf(
        Kind.VERY_LOW to "Glucose < very low.",
        Kind.LOW to "Glucose < low.",
        Kind.HIGH to "Glucose ≥ high.",
        Kind.VERY_HIGH to "Glucose ≥ very high.",
        Kind.EXTREMELY_HIGH to "Glucose ≥ extremely high. Never muted.",
        Kind.FALLING_FAST to "Glucose falling ≥ the set rate.",
        Kind.RISING_FAST to "Glucose rising ≥ the set rate.",
        Kind.NO_DATA to "No reading for the set time.",
    )

    fun notificationId(kind: String) = ID_BASE + when (Kind.group(kind)) { "level" -> 1; "rate" -> 2; else -> 3 }

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("screen", "alerts"), PendingIntent.FLAG_IMMUTABLE)

    /**
     * [who] = the followed person when this mirrors a wearer's alert or follows someone, null on the wearer's phone.
     * [sgv]/[rate] null for no data. [treated]: see [Kind.message].
     */
    fun post(ctx: Context, kind: String, sgv: Int?, rate: Double?, th: Double, at: Long, who: String?, alarm: Boolean, treated: Int) {
        val unit = Kind.unit(kind)
        val thText = if (kind in Kind.RATES) "%.1f".format(th) else th.toInt().toString()
        val value = "${sgv ?: "?"} mg/dL ${Trend.arrow(rate)}"
        val text = when (kind) {
            Kind.VERY_LOW, Kind.LOW -> "$value · < $thText $unit"
            Kind.HIGH, Kind.VERY_HIGH, Kind.EXTREMELY_HIGH -> "$value · ≥ $thText $unit"
            Kind.FALLING_FAST -> "$value · falling ${"%.1f".format(-(rate ?: 0.0))} ≥ $thText $unit"
            Kind.RISING_FAST -> "$value · rising ${"%.1f".format(rate ?: 0.0)} ≥ $thText $unit"
            else -> "Nothing received for $thText min" + (sgv?.let { " · last $it mg/dL" } ?: "")
        }
        val b = Notification.Builder(ctx, channelId(kind, alarm))
            .setSmallIcon(R.drawable.ic_stat_sukrara)
            .setColor(if (isLow(kind)) Range.RED else Range.ORANGE)
            .setContentTitle(Kind.message(kind, treated))
            .setContentText(text)
            .setContentIntent(open(ctx))
            .setAutoCancel(true)
            .setWhen(at * 1000)
            .setShowWhen(true)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
        who?.let { b.setSubText(it) }
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(notificationId(kind), b.build()) }
    }

    fun cancel(ctx: Context, kind: String) = ctx.getSystemService(NotificationManager::class.java).cancel(notificationId(kind))
}
