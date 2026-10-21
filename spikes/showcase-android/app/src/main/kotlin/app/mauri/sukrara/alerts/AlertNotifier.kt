// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
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
    const val SILENT_GROUP = "alert_silent"

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
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, ctx.getString(R.string.channel_group_alerts)))
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ALARM, ctx.getString(R.string.channel_group_alerts_alarm)))
        for (k in Kind.ALL) for (alarm in listOf(false, true)) {
            nm.createNotificationChannel(NotificationChannel(channelId(k, alarm), label(ctx, k), NotificationManager.IMPORTANCE_HIGH).apply {
                group = if (alarm) GROUP_ALARM else GROUP
                description = ctx.getString(if (alarm) R.string.channel_alert_description_alarm else R.string.channel_alert_description,
                    ctx.getString(DESCRIPTIONS.getValue(k)))
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
        Kind.VERY_LOW to R.string.channel_alert_very_low,
        Kind.LOW to R.string.channel_alert_low,
        Kind.HIGH to R.string.channel_alert_high,
        Kind.VERY_HIGH to R.string.channel_alert_very_high,
        Kind.EXTREMELY_HIGH to R.string.channel_alert_extremely_high,
        Kind.FALLING_FAST to R.string.channel_alert_falling_fast,
        Kind.RISING_FAST to R.string.channel_alert_rising_fast,
        Kind.NO_DATA to R.string.channel_alert_no_data,
    )

    /** The alert's name in the app's language ("Very low"): channel names, the sound's title. */
    fun label(ctx: Context, k: String): String = ctx.getString(when (k) {
        Kind.VERY_LOW -> R.string.alert_kind_very_low; Kind.LOW -> R.string.alert_kind_low; Kind.HIGH -> R.string.alert_kind_high
        Kind.VERY_HIGH -> R.string.alert_kind_very_high; Kind.EXTREMELY_HIGH -> R.string.alert_kind_extremely_high
        Kind.FALLING_FAST -> R.string.alert_kind_falling_fast; Kind.RISING_FAST -> R.string.alert_kind_rising_fast
        else -> R.string.alert_kind_no_data
    })

    /**
     * [Kind.message] in the app's language: the engine's structured headline (kind + [treated], see [AlertEngine.Fire])
     * worded here, so the engine and its timeline scenarios stay Android-free and in English.
     */
    fun headline(ctx: Context, k: String, treated: Int): String {
        val base = ctx.getString(when (k) {
            Kind.VERY_LOW -> R.string.alert_headline_very_low; Kind.LOW -> R.string.alert_headline_low
            Kind.HIGH -> R.string.alert_headline_high; Kind.VERY_HIGH -> R.string.alert_headline_very_high
            Kind.EXTREMELY_HIGH -> R.string.alert_headline_extremely_high
            Kind.FALLING_FAST -> R.string.alert_kind_falling_fast; Kind.RISING_FAST -> R.string.alert_kind_rising_fast
            else -> R.string.alert_headline_no_data
        })
        val more = when {
            treated <= 0 || k !in Kind.LEVELS -> return base
            k in Kind.LOW_SIDE -> if (treated == 1) R.string.alert_context_eat_more else R.string.alert_context_eat_more_again
            else -> if (treated == 1) R.string.alert_context_after_insulin else R.string.alert_context_after_more_insulin
        }
        return ctx.getString(R.string.alert_headline_with_context, base, ctx.getString(more))
    }

    fun notificationId(kind: String) = ID_BASE + when (Kind.group(kind)) { "level" -> 1; "rate" -> 2; else -> 3 }

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("screen", "alerts"), PendingIntent.FLAG_IMMUTABLE)

    /**
     * [who] = the followed person when this mirrors a wearer's alert or follows someone, null on the wearer's phone.
     * [sgv]/[rate] null for no data. [treated]: see [Kind.message]. [silent]: an alert shown again after a restart
     * (it already rang; see Alerts.resume).
     */
    fun post(ctx: Context, kind: String, sgv: Int?, rate: Double?, th: Double, at: Long, who: String?, alarm: Boolean, treated: Int,
             silent: Boolean = false, actions: Actions? = null) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(notificationId(kind),
            build(ctx, kind, sgv, rate, th, at, who, alarm, treated, silent, actions)) }
    }

    /**
     * The alert's action buttons (at most three, developer.android.com/develop/ui/views/notifications/build-notification
     * "Add action buttons": "up to three action buttons that let the user respond quickly, such as to snooze a
     * reminder"), each a broadcast to [AlertActionReceiver] so nothing opens: *Mute [muteMin] min* and *Mute for…*
     * (a direct reply, "Add a direct reply action": text entered in the notification, delivered without opening an
     * activity) when [mute] (never very low or extremely high), *Slow to [slowMin] min* when non-null.
     */
    data class Actions(val mute: Boolean, val muteMin: Int, val slowMin: Int?)

    private fun action(ctx: Context, kind: String, what: String, code: Int, mutable: Boolean = false, min: Int = 0) =
        PendingIntent.getBroadcast(ctx, 200 + notificationId(kind) * 4 + code,
            Intent(ctx, AlertActionReceiver::class.java).setAction(what).putExtra(AlertActionReceiver.KIND, kind).putExtra(AlertActionReceiver.MIN, min),
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    private fun Notification.Builder.actions(ctx: Context, kind: String, a: Actions): Notification.Builder {
        if (a.mute) addAction(Notification.Action.Builder(null, ctx.getString(R.string.alert_action_mute, a.muteMin),
            action(ctx, kind, AlertActionReceiver.MUTE, 0, min = a.muteMin)).build())
        a.slowMin?.let { m -> addAction(Notification.Action.Builder(null, ctx.getString(R.string.alert_action_slow, m),
            action(ctx, kind, AlertActionReceiver.SLOW, 1)).build()) }
        // the reply's PendingIntent must be mutable: the system adds the typed text to it
        if (a.mute) addAction(Notification.Action.Builder(null, ctx.getString(R.string.alert_action_mute_for),
            action(ctx, kind, AlertActionReceiver.MUTE_FOR, 2, mutable = true))
            .addRemoteInput(RemoteInput.Builder(AlertActionReceiver.TYPED).setLabel(ctx.getString(R.string.alert_action_mute_for_label)).build())
            .build())
        return this
    }

    /** The minutes in a typed *Mute for…* reply ("20", "20 min"), or null when there are none or they're out of range. */
    fun minutes(typed: CharSequence?): Int? = typed?.let { Regex("\\d{1,4}").find(it)?.value?.toInt() }?.takeIf { it in AlertSettings.MUTE_RANGE }

    fun build(ctx: Context, kind: String, sgv: Int?, rate: Double?, th: Double, at: Long, who: String?, alarm: Boolean, treated: Int,
              silent: Boolean = false, actions: Actions? = null): Notification {
        val unit = Kind.unit(kind)
        val thText = if (kind in Kind.RATES) "%.1f".format(th) else th.toInt().toString()
        val value = "${sgv ?: "?"} mg/dL ${Trend.arrow(rate)}"
        val text = when (kind) {
            // The value and arrow only; the title already names the alert (no threshold, no "from …").
            Kind.VERY_LOW, Kind.LOW, Kind.HIGH, Kind.VERY_HIGH, Kind.EXTREMELY_HIGH -> value
            Kind.FALLING_FAST -> "$value · " + ctx.getString(R.string.alert_text_falling, "%.1f".format(-(rate ?: 0.0)) + " $unit")
            Kind.RISING_FAST -> "$value · " + ctx.getString(R.string.alert_text_rising, "%.1f".format(rate ?: 0.0) + " $unit")
            else -> sgv?.let { ctx.getString(R.string.alert_text_no_data_last, thText, it) } ?: ctx.getString(R.string.alert_text_no_data, thText)
        }
        val b = Notification.Builder(ctx, channelId(kind, alarm))
            .setSmallIcon(R.drawable.ic_stat_sukrara)
            .setColor(if (isLow(kind)) Range.RED else Range.ORANGE)
            .setContentTitle(headline(ctx, kind, treated))
            .setContentText(text)
            .setContentIntent(open(ctx))
            .setAutoCancel(true)
            .setWhen(at * 1000)
            .setShowWhen(true)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
        // silent: what NotificationCompat.setSilent does: a group member that defers alerting to a summary that never
        // exists (NotificationRecord: suppressAlertingDueToGrouping); the channel's sound and vibration are skipped
        if (silent) b.setGroup(SILENT_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        who?.let { b.setSubText(it) }
        actions?.let { b.actions(ctx, kind, it) }
        return b.build()
    }

    fun cancel(ctx: Context, kind: String) = ctx.getSystemService(NotificationManager::class.java).cancel(notificationId(kind))
}
