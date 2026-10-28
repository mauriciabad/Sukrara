// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.notify

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.monitor.R
import app.mauri.sukrara.monitor.alerts.AlertChannels

/**
 * Every notification channel and group of the app, in one table.
 *
 * NEVER rename or reuse an id: Android keeps the person's choices (sound, importance, "override Do Not Disturb") per
 * channel id, so a renamed channel silently resets them to our defaults — for alert channels that can mean a muted
 * alarm the person had made loud. New behaviour gets a new id; a removed channel is deleted explicitly.
 */
object Channels {
    enum class Group(
        val id: String,
        @param:StringRes val nameRes: Int,
    ) {
        /** Glucose alerts that notify. Never shared with [INFO]: alerts are never bundled with other notifications. */
        ALERTS("alerts", R.string.monitor_group_alerts),

        /** Glucose alerts at alarm level (alarm volume, full screen). */
        ALERTS_ALARM("alerts_alarm", R.string.monitor_group_alerts_alarm),

        /** Everything that isn't an alert. */
        INFO("info", R.string.monitor_group_info),
    }

    enum class Kind { ALERT, ALARM, INFO }

    data class Spec(
        val id: String,
        @param:StringRes val nameRes: Int,
        val importance: Int,
        val group: Group,
        val kind: Kind,
        /** The alert kind (contract id) of an alert channel. */
        val alertKind: String? = null,
    )

    const val GLUCOSE = "glucose"
    const val STATUS = "status"
    const val REQUESTS = "requests"
    const val SENSOR = "sensor"
    const val SENSOR_COUNTDOWN = "sensor.countdown"
    const val PEN_RESULTS = "pen.results"
    const val PEN_CHECK = "pen.check"

    /** The alert kinds of `contracts/alerts/SPEC.md`, in its order. */
    val ALERT_KINDS =
        listOf("very_low", "low", "high", "very_high", "extremely_high", "falling_fast", "rising_fast", "no_data")

    fun alert(kind: String) = "alert.$kind"

    fun alarm(kind: String) = "alert_alarm.$kind"

    val ALL: List<Spec> =
        listOf(
            // the ongoing glucose notification: silent, no badge (it changes every minute)
            Spec(GLUCOSE, R.string.monitor_channel_glucose, NotificationManager.IMPORTANCE_LOW, Group.INFO, Kind.INFO),
            Spec(STATUS, R.string.monitor_channel_status, NotificationManager.IMPORTANCE_HIGH, Group.INFO, Kind.INFO),
            Spec(REQUESTS, R.string.monitor_channel_requests, NotificationManager.IMPORTANCE_DEFAULT, Group.INFO, Kind.INFO),
            Spec(SENSOR, R.string.monitor_channel_sensor, NotificationManager.IMPORTANCE_HIGH, Group.INFO, Kind.INFO),
            Spec(SENSOR_COUNTDOWN, R.string.monitor_channel_sensor_countdown, NotificationManager.IMPORTANCE_LOW, Group.INFO, Kind.INFO),
            Spec(PEN_RESULTS, R.string.monitor_channel_pen_results, NotificationManager.IMPORTANCE_DEFAULT, Group.INFO, Kind.INFO),
            Spec(PEN_CHECK, R.string.monitor_channel_pen_check, NotificationManager.IMPORTANCE_DEFAULT, Group.INFO, Kind.INFO),
        ) +
            ALERT_KINDS.map { Spec(alert(it), alertName(it), NotificationManager.IMPORTANCE_HIGH, Group.ALERTS, Kind.ALERT, it) } +
            ALERT_KINDS.map { Spec(alarm(it), alarmName(it), NotificationManager.IMPORTANCE_HIGH, Group.ALERTS_ALARM, Kind.ALARM, it) }

    /**
     * Creates every group and channel (idempotent: Android keeps the person's edits of an existing channel and only
     * updates its name). Alert channels always get their full setup ([AlertChannels]: the Sukrara sound, the volume's
     * usage, the kind's vibration, Do Not Disturb for alarms): Android fixes it at creation, so it is never optional.
     */
    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroups(Group.entries.map { NotificationChannelGroup(it.id, context.getString(it.nameRes)) })
        nm.createNotificationChannels(ALL.map { channel(context, it) })
    }

    /**
     * Only the `glucose` and `status` channels (the service's start, before the app is ready). Never the alert
     * channels: Android fixes a channel's sound at creation, so they are created once, with their sounds, by [create].
     */
    fun createCore(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup(Group.INFO.id, context.getString(Group.INFO.nameRes)))
        nm.createNotificationChannels(ALL.filter { it.id == GLUCOSE || it.id == STATUS }.map { channel(context, it) })
    }

    private fun channel(
        context: Context,
        spec: Spec,
    ) = NotificationChannel(spec.id, context.getString(spec.nameRes), spec.importance).apply {
        group = spec.group.id
        when (spec.kind) {
            Kind.INFO -> {
                setShowBadge(spec.id != GLUCOSE && spec.id != SENSOR_COUNTDOWN)
            }

            Kind.ALERT, Kind.ALARM -> {
                AlertChannels.configure(this, context, AlertKind.fromWire(spec.alertKind!!)!!, spec.kind == Kind.ALARM)
            }
        }
    }

    @StringRes
    private fun alertName(kind: String): Int =
        when (kind) {
            "very_low" -> R.string.monitor_channel_alert_very_low
            "low" -> R.string.monitor_channel_alert_low
            "high" -> R.string.monitor_channel_alert_high
            "very_high" -> R.string.monitor_channel_alert_very_high
            "extremely_high" -> R.string.monitor_channel_alert_extremely_high
            "falling_fast" -> R.string.monitor_channel_alert_falling_fast
            "rising_fast" -> R.string.monitor_channel_alert_rising_fast
            "no_data" -> R.string.monitor_channel_alert_no_data
            else -> error("unknown alert kind $kind")
        }

    @StringRes
    private fun alarmName(kind: String): Int =
        when (kind) {
            "very_low" -> R.string.monitor_channel_alarm_very_low
            "low" -> R.string.monitor_channel_alarm_low
            "high" -> R.string.monitor_channel_alarm_high
            "very_high" -> R.string.monitor_channel_alarm_very_high
            "extremely_high" -> R.string.monitor_channel_alarm_extremely_high
            "falling_fast" -> R.string.monitor_channel_alarm_falling_fast
            "rising_fast" -> R.string.monitor_channel_alarm_rising_fast
            "no_data" -> R.string.monitor_channel_alarm_no_data
            else -> error("unknown alert kind $kind")
        }
}

/** Every notification id of the app in one table, so two features never post over each other. */
object NotificationIds {
    /** The ongoing glucose notification (also the monitoring service's foreground notification). */
    const val GLUCOSE = 1

    /** "Monitoring stopped — tap to resume". */
    const val MONITORING_STOPPED = 2
    const val SENSOR = 10
    const val SENSOR_COUNTDOWN = 11
    const val PEN_RESULTS = 20
    const val PEN_CHECK = 21
    const val REQUESTS = 30

    /** Alerts: the `alerts` notification group's summary, then one slot per alert group (level, rate, no data). */
    const val ALERTS_SUMMARY = 1000
    const val ALERTS_BASE = 1001

    /** The `info` notification group's summary. */
    const val INFO_SUMMARY = 1100
}
