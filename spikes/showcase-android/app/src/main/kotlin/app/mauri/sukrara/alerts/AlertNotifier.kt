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
import app.mauri.sukrara.core.Durations
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
     * The alert's action buttons, exactly two on every kind (round f30; very low and extremely high included), each a
     * broadcast to [AlertActionReceiver] so nothing opens. Android: "A notification can offer up to three action
     * buttons that let the user respond quickly, such as to snooze a reminder … But these action buttons must not
     * duplicate the action performed when the user taps the notification"
     * (developer.android.com/develop/ui/views/notifications/build-notification#Actions); the tap opens the Alerts page.
     * 1. *Mute…*: a direct reply ("Add a direct reply action": "The direct reply action appears as an additional button
     *    in the notification that opens a text input … the system attaches the text response to the intent"), its
     *    lengths [mute] (minutes, the setting's first) as one-tap choices (RemoteInput.Builder#setChoices) worded by
     *    Durations.text, and a typed length too (Durations.parse).
     * 2. *Remind less* when [relax] (it would space the reminders), or *Remind as usual* in the same slot when the side
     *    reminds less ([relaxedMin] > 0); the interval goes in the notification's secondary text, not the button.
     */
    data class Actions(val mute: List<Int>, val relax: Boolean, val relaxedMin: Int = 0)

    private fun action(ctx: Context, kind: String, what: String, code: Int, mutable: Boolean = false) =
        PendingIntent.getBroadcast(ctx, 200 + notificationId(kind) * 4 + code,
            Intent(ctx, AlertActionReceiver::class.java).setAction(what).putExtra(AlertActionReceiver.KIND, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    private fun Notification.Builder.actions(ctx: Context, kind: String, a: Actions): Notification.Builder {
        muteFor(ctx, kind, ctx.getString(R.string.alert_action_mute), a.mute)
        if (a.relaxedMin > 0) addAction(Notification.Action.Builder(null, ctx.getString(R.string.alert_action_remind_usual),
            action(ctx, kind, AlertActionReceiver.UNRELAX, 1)).build())
        else if (a.relax) addAction(Notification.Action.Builder(null, ctx.getString(R.string.alert_action_remind_less),
            action(ctx, kind, AlertActionReceiver.RELAX, 1)).build())
        return this
    }

    /**
     * *Mute…* (or *Change time* on the muted notification): a direct reply with the length, [choices] (minutes) as one-tap
     * chips. The reply's PendingIntent must be mutable: the system adds the typed text to it.
     */
    private fun Notification.Builder.muteFor(ctx: Context, kind: String, title: String, choices: List<Int>) =
        addAction(Notification.Action.Builder(null, title, action(ctx, kind, AlertActionReceiver.MUTE, 2, mutable = true))
            .addRemoteInput(RemoteInput.Builder(AlertActionReceiver.TYPED).setLabel(ctx.getString(R.string.alert_action_mute_for_label))
                // RemoteInput can't ask for a number pad (no input type in RemoteInput.Builder, API 37 SDK: only setChoices,
                // setAllowFreeFormInput, setAllowDataType, setEditChoicesBeforeSending, setLabel). So the common lengths
                // come as choices, which SystemUI shows as one-tap reply chips (sent at once), and typed ones still work.
                .setChoices(choiceTexts(choices).toTypedArray())
                .setAllowFreeFormInput(true)
                .setEditChoicesBeforeSending(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED)
                .build())
            .build())

    /** The reply chips of *Mute…*: "30 min", "15 min", "1 h", "2 h" (Durations.text in the app's language). */
    fun choiceTexts(choices: List<Int>): List<String> = choices.map { Durations.text(it) }

    /** The minutes of a *Mute…* reply (a chip or typed: "20", "45 min", "1 h 30"), or null when it isn't a length in range. */
    fun minutes(typed: CharSequence?): Int? = typed?.let { Durations.parse(it.toString()) }?.takeIf { it in AlertSettings.MUTE_RANGE }

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
        // the follower's "who", and the interval of an alert that reminds less ("Reminders every 30 min"): secondary text, not the button
        val relaxed = actions?.relaxedMin?.takeIf { it > 0 }?.let { ctx.getString(R.string.alert_relaxed_subtext, Durations.text(it)) }
        listOfNotNull(who, relaxed).takeIf { it.isNotEmpty() }?.let { b.setSubText(it.joinToString(" · ")) }
        actions?.let { b.actions(ctx, kind, it) }
        return b.build()
    }

    fun cancel(ctx: Context, kind: String) = ctx.getSystemService(NotificationManager::class.java).cancel(notificationId(kind))

    /**
     * After *Mute…*: the alert's notification becomes "Low alerts muted" / "Until 10:42" with a count-down chronometer
     * to the mute's end (round f31; the user, 2026-10-22: "muted alerts should show a countdown"): `when` = the end,
     * Notification.Builder#setUsesChronometer + #setChronometerCountDown ("the chronometer will count down to the
     * time set in setWhen"), so SystemUI ticks it with no re-post. Silent, gone at the mute's end (setTimeoutAfter),
     * when the side is back in range by the margin, or on Unmute. Android asks for this after a direct reply
     * (developer.android.com/develop/ui/views/notifications/build-notification#retrieve-user-input: "After you process
     * the text, you must update the notification … to hide the direct reply UI and confirm to the user that their reply
     * is received and processed"); cancelling instead left SystemUI's copy with the typed minutes and the Mute buttons
     * in the shade (validation 21), while the plain Mute's notification went away.
     *
     * Its two buttons are the ways out (round f29; user, 2026-10-21: "There's no way to unmute!"): *Unmute* (alerts at
     * once if still out of range) and *Change time* (a new length from now, the same direct reply as *Mute…*, [choices]). The
     * Material snackbar / Android notification guidance pairs an action that silences something with an immediate
     * undo (m3.material.io/components/snackbar/guidelines: "Snackbars can offer a single action … such as Undo";
     * developer.android.com/develop/ui/views/notifications: actions "let the user respond quickly").
     */
    fun postMuted(ctx: Context, kind: String, sgv: Int?, rate: Double?, th: Double, at: Long, who: String?, alarm: Boolean,
                  until: Long, now: Long, choices: List<Int>) {
        val base = build(ctx, kind, sgv, rate, th, at, who, alarm, 0, silent = true)
        val n = Notification.Builder.recoverBuilder(ctx, base)
            .setContentTitle(MuteCopy.notifTitle(ctx, kind))
            .setContentText(MuteCopy.notifText(ctx, app.mauri.sukrara.graph.Freshness.timeText(until, now)))
            .setWhen(until * 1000)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(((until - now) * 1000).coerceAtLeast(1_000))
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.alert_action_unmute),
                action(ctx, kind, AlertActionReceiver.UNMUTE, 3)).build())
            .muteFor(ctx, kind, ctx.getString(R.string.alert_action_change), choices)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(notificationId(kind), n) }
    }
}
