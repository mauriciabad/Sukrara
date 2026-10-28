// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.notify

import androidx.annotation.StringRes
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.NotMonitoringReason
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.TrendMark
import app.mauri.sukrara.monitor.R
import java.time.Instant

/** Text from resources, resolved when the notification is built (so this model stays testable on the JVM). */
data class UiText(
    @param:StringRes val res: Int,
    val args: List<Any> = emptyList(),
)

/**
 * The status-bar settings and what Android allows, as the glucose notification needs them: [chip] = Settings › Status
 * bar › *Live Update chip*; [promotionAllowed] = Android lets the app post Live Updates now; [numberIcon] = *Number
 * icon* (the small icon is the value while there's no chip); [cardKey] = everything the custom card draws besides
 * the hub state (treatments, graph settings, theme, the minute), so equal contents mean the same card.
 */
data class NotificationPrefs(
    val chip: Boolean = true,
    val promotionAllowed: Boolean = false,
    val numberIcon: Boolean = true,
    val cardKey: Any? = null,
)

/**
 * What the glucose notification shows, decided from the hub state and [NotificationPrefs] alone. Equal contents = the
 * same notification (the notifier skips re-posting it). The value, trend mark and freshness come from the card's face
 * rule ([face], `GlucoseCard.face`), never formatted here.
 */
data class LiveNotificationContent(
    val title: UiText,
    val text: UiText?,
    /** The value is struck through (5–20 min old). */
    val strike: Boolean,
    /** The Live Update chip's text ("138"; its icon is the arrow); null = icon only. */
    val chip: UiText?,
    val icon: Icon,
    /** The rate (mg/dL/min) the [Icon.TREND] arrow points for. */
    val trendRate: Double? = null,
    /** The reading's time, shown once it's late. */
    val readingAt: Instant?,
    val tap: Target,
    val actions: List<Action>,
    /** How a value shows; the states without one are always [Layout.TEXT]. */
    val layout: Layout = Layout.TEXT,
    /** The face's decision for the value ([Icon.NUMBER], the card, MetricStyle); null without a value. */
    val look: GlucoseLook? = null,
    /** The value's reading (mg/dL), for the card's range colour and the metric's style. */
    val mgdl: Int? = null,
    /** [NotificationPrefs.cardKey] while [layout] is [Layout.CARD]. */
    val cardKey: Any? = null,
) {
    /** [TREND] = the renderer's arrow for [trendRate]; [NUMBER] = the value itself; the others are the states' icons. */
    enum class Icon { TREND, NO_TREND, NUMBER, NO_READINGS, SENSOR_ENDED, APP }

    enum class Layout {
        /** A promoted Live Update: the chip (arrow + value), MetricStyle expanded on Android 17. */
        LIVE_UPDATE,

        /** Colorized in the range colour with the renderer's header row and chart (no chip). */
        CARD,

        /** Title and text only (no value, or nothing better allowed). */
        TEXT,
    }

    sealed interface Target {
        /** The quick "Add treatment" dialog; Carbs focused while a low is going on. */
        data class QuickTreatment(val focusCarbs: Boolean) : Target

        data object OpenApp : Target

        data object SensorSetup : Target
    }

    data class Action(val label: UiText, val target: Target)

    companion object {
        private val LOW_KINDS = setOf("low", "very_low")
        private val OPEN_APP = Action(UiText(R.string.monitor_action_open_app), Target.OpenApp)

        /**
         * The notification for [state] at [now]. Order: setup not done → the sensor's own states (ended, warming up)
         * → not monitoring yet → waiting for a first value → the value as the face shows it.
         */
        fun from(
            state: GlucoseState,
            now: Instant,
            face: (GlucoseState, Instant) -> GlucoseLook,
            prefs: NotificationPrefs = NotificationPrefs(),
        ): LiveNotificationContent {
            if (state.notMonitoring == NotMonitoringReason.SetupIncomplete) return notMonitoring(state)
            val look = face(state, now)
            val detail = detail(state)
            return when (look.state) {
                GlucoseLook.State.ENDED -> {
                    plain(
                        UiText(R.string.monitor_notif_sensor_ended),
                        detail,
                        Icon.SENSOR_ENDED,
                        if (state.role == Role.Wearer) Target.SensorSetup else Target.OpenApp,
                        actions =
                            if (state.role ==
                                Role.Wearer
                            ) {
                                listOf(Action(UiText(R.string.monitor_action_replace_sensor), Target.SensorSetup))
                            } else {
                                emptyList()
                            },
                    )
                }

                GlucoseLook.State.WARMING_UP -> {
                    plain(UiText(R.string.monitor_notif_warming_up, listOf(look.warmup.orEmpty())), detail, Icon.APP, Target.OpenApp)
                }

                GlucoseLook.State.NONE -> {
                    if (state.notMonitoring != null) {
                        notMonitoring(state)
                    } else {
                        plain(UiText(R.string.monitor_notif_waiting), detail, Icon.APP, quickTreatment(state), listOf(OPEN_APP))
                    }
                }

                GlucoseLook.State.NO_READINGS -> {
                    val at = state.latest!!.t
                    LiveNotificationContent(
                        title = UiText(R.string.monitor_notif_no_readings_since, listOf(at)),
                        text = detail,
                        strike = false,
                        chip = null,
                        icon = Icon.NO_READINGS,
                        readingAt = at,
                        tap = quickTreatment(state),
                        actions = listOf(OPEN_APP),
                    )
                }

                GlucoseLook.State.VALUE -> {
                    value(state, look, detail, prefs)
                }
            }
        }

        /**
         * A value: a Live Update when the chip is wanted and allowed, else the colorized card. The chip's arrow is its
         * icon (the renderer's), never a text arrow too: two arrows could disagree. It can't strike text through: from
         * 5 min it shows the reading's time (with the ring icon) instead of a bare number that would read as current.
         * Without the chip the small icon is the number itself when *Number icon* is on.
         */
        private fun value(
            state: GlucoseState,
            look: GlucoseLook,
            detail: UiText?,
            prefs: NotificationPrefs,
        ): LiveNotificationContent {
            val reading = state.latest!!
            val live = prefs.chip && prefs.promotionAllowed
            val chip =
                when {
                    !live -> null
                    look.strike -> UiText(R.string.monitor_chip_stale, listOf(reading.t))
                    else -> UiText(R.string.monitor_chip_value, listOf(look.text!!))
                }
            val icon =
                when {
                    !live && prefs.numberIcon -> Icon.NUMBER
                    look.trend == TrendMark.ARROW -> Icon.TREND
                    else -> Icon.NO_TREND
                }
            return LiveNotificationContent(
                title = UiText(R.string.monitor_notif_value, listOf(look.text!!, look.arrowText!!)),
                text = if (look.fresh.showTime) UiText(R.string.monitor_notif_reading_at, listOf(reading.t)) else detail,
                strike = look.strike,
                chip = chip,
                icon = icon,
                trendRate = look.rate.takeIf { look.trend == TrendMark.ARROW },
                readingAt = if (look.fresh.showTime) reading.t else null,
                tap = quickTreatment(state),
                actions = listOf(OPEN_APP),
                layout = if (live) Layout.LIVE_UPDATE else Layout.CARD,
                look = look,
                mgdl = reading.mgdl,
                cardKey = if (live) null else prefs.cardKey,
            )
        }

        private fun notMonitoring(state: GlucoseState): LiveNotificationContent {
            val setUp =
                if (state.role == Role.Follower) {
                    Action(UiText(R.string.monitor_action_open_app), Target.OpenApp)
                } else {
                    Action(UiText(R.string.monitor_action_set_up_sensor), Target.SensorSetup)
                }
            return plain(UiText(R.string.monitor_notif_not_monitoring), null, Icon.APP, setUp.target, listOf(setUp))
        }

        private fun plain(
            title: UiText,
            text: UiText?,
            icon: Icon,
            tap: Target,
            actions: List<Action> = emptyList(),
        ) = LiveNotificationContent(title, text, strike = false, chip = null, icon = icon, readingAt = null, tap = tap, actions = actions)

        /** The second line for a sensor that needs something (shown under the value or state). */
        private fun detail(state: GlucoseState): UiText? =
            when (state.sensor) {
                SensorState.BluetoothOff -> UiText(R.string.monitor_notif_bluetooth_off)
                SensorState.CoreMissing -> UiText(R.string.monitor_notif_core_missing)
                else -> null
            }

        private fun quickTreatment(state: GlucoseState) =
            Target.QuickTreatment(
                focusCarbs = state.alerts.firingKinds.any { it in LOW_KINDS },
            )
    }
}
