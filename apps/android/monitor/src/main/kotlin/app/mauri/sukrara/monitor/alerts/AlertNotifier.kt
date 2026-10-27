// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import app.mauri.sukrara.alerts.AlertGroup
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Firing
import app.mauri.sukrara.alerts.MuteLine
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.core.model.AlertDeliveryProblem
import app.mauri.sukrara.core.model.DurationWords
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.graph.Clock
import app.mauri.sukrara.monitor.AlertActionReceiver
import app.mauri.sukrara.monitor.R
import app.mauri.sukrara.monitor.notify.NotificationGroups
import java.util.Locale

/**
 * The alert notifications ([AlertPresenter]): one slot per [AlertGroup] (level, rate, no data), all in the `alerts`
 * notification group ([NotificationGroups]). Self-contained text: the headline names the alert, the text gives the
 * value, the arrow and what it means; the actions work from the lock screen without opening the app
 * ([AlertActionReceiver]). Alarm-level kinds (the kind's *alarm* setting) also get a full-screen intent, only when
 * Android allows it ([NotificationManager.canUseFullScreenIntent]; otherwise it is a heads-up and the reliability row
 * says so). The tap opens the app.
 */
class AlertNotifier(private val ctx: Context) : AlertPresenter {
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private val words = object : AlertWords {
        override fun get(name: String) = ctx.getString(ctx.resources.getIdentifier(name, "string", ctx.packageName))

        override val durations = DurationWords(
            minutes = get("duration_minutes"),
            hours = get("duration_hours"),
            hoursMinutes = get("duration_hours_minutes"),
            days = get("duration_days"),
            textMinutes = get("duration_text_minutes"),
            textHours = get("duration_text_hours"),
            textHoursMinutes = get("duration_text_hours_minutes"),
        )
    }

    override fun show(firing: Firing, settings: AlertSettings, silent: Boolean, who: String?, relaxedMin: Int) {
        val kind = firing.kind
        val alarm = settings.rule(kind).alarm
        val b = base(kind, alarm, silent)
            .setContentTitle(headline(kind, firing.treated))
            .setContentText(text(firing))
            .setWhen(firing.at * 1000)
            .setShowWhen(true)
            .setAutoCancel(true)
        val relaxed = relaxedMin.takeIf { it > 0 }?.let { ctx.getString(R.string.alert_relaxed_subtext, Durations.text(it.toLong(), words.durations)) }
        listOfNotNull(who, relaxed).takeIf { it.isNotEmpty() }?.let { b.setSubText(it.joinToString(" · ")) }
        b.addMuteAction(kind, ctx.getString(R.string.alert_action_mute), settings.muteChoices())
        when {
            relaxedMin > 0 -> b.addAction(action(kind, AlertActionReceiver.REMIND_AS_USUAL, R.string.alert_action_remind_usual))
            settings.canRelax(kind) -> b.addAction(action(kind, AlertActionReceiver.REMIND_LESS, R.string.alert_action_remind_less))
        }
        if (alarm && !silent && nm.canUseFullScreenIntent()) b.setFullScreenIntent(openApp(), true)
        post(kind.group, b.build(), AlertChannels.id(kind, alarm))
    }

    override fun showMuted(group: AlertGroup, side: Side, kind: AlertKind, until: Long, settings: AlertSettings, now: Long) {
        val alarm = settings.rule(kind).alarm
        val b = base(kind, alarm, silent = true)
            .setContentTitle(AlertCopy.line(MuteLine.SideMuted(side, null), words))
            .setContentText(AlertCopy.line(MuteLine.NotifUntil(until), words, ::clockTime))
            .setWhen(until * 1000)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(((until - now) * 1000).coerceAtLeast(1_000))
            .addAction(action(kind, AlertActionReceiver.UNMUTE, R.string.alert_action_unmute))
        b.addMuteAction(kind, ctx.getString(R.string.alert_action_change), settings.muteChoices())
        post(group, b.build(), AlertChannels.id(kind, alarm))
    }

    override fun clear(group: AlertGroup) = NotificationGroups.cancel(ctx, idOf(group), NotificationGroups.Group.ALERTS)

    override fun deliveryProblems(alarmKinds: Set<AlertKind>): Set<AlertDeliveryProblem> = buildSet {
        if (!nm.areNotificationsEnabled()) add(AlertDeliveryProblem.NotificationsOff)
        val blocked = AlertChannels.ALL.any { id -> nm.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE }
        if (blocked) add(AlertDeliveryProblem.AlertChannelBlocked)
        if (alarmKinds.isNotEmpty() && !nm.canUseFullScreenIntent()) add(AlertDeliveryProblem.FullScreenDenied)
    }

    @Volatile private var channelsReady = false

    private fun post(group: AlertGroup, n: Notification, channelId: String) {
        // the channels exist before the first alert of this process (also created by AlertsModule.attach)
        if (!channelsReady) {
            AlertChannels.ensure(ctx)
            channelsReady = true
        }
        NotificationGroups.post(ctx, idOf(group), n, NotificationGroups.Group.ALERTS, channelId)
    }

    private fun base(kind: AlertKind, alarm: Boolean, silent: Boolean): Notification.Builder =
        Notification.Builder(ctx, AlertChannels.id(kind, alarm))
            .setSmallIcon(ctx.applicationInfo.icon)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
            .setDeleteIntent(broadcast(kind, AlertActionReceiver.TIDY, mutable = false))
            .setGroup(NotificationGroups.Group.ALERTS.key)
            // silent = what NotificationCompat.setSilent does: a child that defers alerting to the summary, which never
            // alerts (GROUP_ALERT_CHILDREN); a ringing alert keeps GROUP_ALERT_ALL and rings as a child
            .apply { if (silent) setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY) }

    private fun headline(kind: AlertKind, treated: Int): String {
        val base = words.get("alert_headline_${kind.wire}")
        val context = when {
            treated <= 0 || !kind.isLevel -> return base
            kind.side == Side.LOW -> if (treated == 1) R.string.alert_context_eat_more else R.string.alert_context_eat_more_again
            else -> if (treated == 1) R.string.alert_context_after_insulin else R.string.alert_context_after_more_insulin
        }
        return ctx.getString(R.string.alert_headline_with_context, base, ctx.getString(context))
    }

    private fun text(f: Firing): String {
        val r = f.reading
        if (f.kind == AlertKind.NO_DATA) {
            val since = Durations.text(((f.at - (r?.t ?: f.at)) / 60).coerceAtLeast(f.threshold.toLong()), words.durations)
            return if (r == null) ctx.getString(R.string.alert_text_no_data, since)
            else ctx.getString(R.string.alert_text_no_data_last, since, r.mgdl)
        }
        val value = ctx.getString(R.string.alert_text_value, r?.mgdl ?: 0, arrow(r?.rate))
        val rate = String.format(Locale.getDefault(), "%.1f", kotlin.math.abs(r?.rate ?: 0.0))
        return when (f.kind) {
            AlertKind.FALLING_FAST -> ctx.getString(R.string.alert_text_falling, value, rate)
            AlertKind.RISING_FAST -> ctx.getString(R.string.alert_text_rising, value, rate)
            else -> value
        }
    }

    private fun clockTime(epochS: Long): String = Clock.time(epochS)

    /** *Mute…* / *Change time*: a direct reply with the lengths as one-tap chips (and a typed one). */
    private fun Notification.Builder.addMuteAction(kind: AlertKind, title: String, choices: List<Int>) = addAction(
        Notification.Action.Builder(null, title, broadcast(kind, AlertActionReceiver.MUTE, mutable = true))
            .addRemoteInput(
                RemoteInput.Builder(AlertActionReceiver.TYPED)
                    .setLabel(ctx.getString(R.string.alert_action_mute_for_label))
                    .setChoices(choices.map { Durations.text(it.toLong(), words.durations) }.toTypedArray())
                    .setAllowFreeFormInput(true)
                    .setEditChoicesBeforeSending(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED)
                    .build(),
            )
            .build(),
    )

    private fun action(kind: AlertKind, what: String, label: Int) =
        Notification.Action.Builder(null, ctx.getString(label), broadcast(kind, what, mutable = false)).build()

    /** A broadcast to the non-exported [AlertActionReceiver]; only the direct reply's is mutable (the system adds the text). */
    private fun broadcast(kind: AlertKind, what: String, mutable: Boolean): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        requestCode(kind, what),
        Intent(ctx, AlertActionReceiver::class.java).setAction(what).putExtra(AlertActionReceiver.KIND, kind.wire),
        PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(): PendingIntent {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent().setPackage(ctx.packageName)
        return PendingIntent.getActivity(ctx, 1, launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        /** Notification ids: one per alert group (chunk 10's table reserves 1000+ for alerts; 1000 is the summary). */
        fun idOf(group: AlertGroup): Int = 1001 + group.ordinal

        private fun requestCode(kind: AlertKind, what: String) = 2000 + kind.ordinal * 16 + AlertActionReceiver.ACTIONS.indexOf(what)

        fun kindLabel(ctx: Context, kind: AlertKind): String =
            ctx.getString(ctx.resources.getIdentifier("alert_kind_${kind.wire}", "string", ctx.packageName))

        /** The trend arrow of a rate in mg/dL/min (the Nightscout direction bands). */
        fun arrow(rate: Double?): String = when {
            rate == null -> ""
            rate <= -3 -> "⇊"
            rate <= -2 -> "↓"
            rate <= -1 -> "↘"
            rate < 1 -> "→"
            rate < 2 -> "↗"
            rate < 3 -> "↑"
            else -> "⇈"
        }
    }
}
