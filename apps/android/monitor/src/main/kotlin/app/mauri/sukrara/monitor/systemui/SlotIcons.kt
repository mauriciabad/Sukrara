// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.TrendAngle
import app.mauri.sukrara.graph.TrendMark
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The root status bar's system icons: two slots System UI draws itself (tinted like Wi-Fi and the battery) from
 * drawables in this APK, one per value (System UI's Compose status bar ignores the icon level). What they show is the
 * card's decision ([app.mauri.sukrara.graph.GlucoseCard.face] for `CardSurface.STATUS_ICONS`).
 *
 * The TalkBack texts are also how the System UI module recognises our icons in its row ([isOurs], [isTrend]); they
 * stay English and start with "Sukrara" because System UI reads them too (the module can't load this app's
 * translations there, and another app's glucose icons must never match).
 */
object SlotIcons {
    /** Slot names, distinct from any other app's (System UI keys icons by slot name). */
    const val SLOT_NUMBER = "sukrara_glucose"
    const val SLOT_TREND = "sukrara_glucose_trend"

    /** Our slots in the order they show: the number, then the trend. */
    val ORDER = listOf(SLOT_NUMBER, SLOT_TREND)

    private const val VALUE = "Sukrara glucose "
    private const val NO_READINGS = "Sukrara: no glucose readings since "
    private const val ENDED = "Sukrara: sensor ended"
    private const val TREND = "Sukrara glucose trend "

    /** One of our icons, by its description. */
    fun isOurs(description: String) = listOf(VALUE, NO_READINGS, ENDED).any(description::startsWith)

    /** The trend icon beside a number. */
    fun isTrend(description: String) = description.startsWith(TREND)

    /** A number icon (the module colours these by range). */
    fun isValue(description: String) = description.startsWith(VALUE) && !isTrend(description)

    /**
     * What the two slots show: drawable names (null = remove the slot) and what TalkBack says ([desc] on the number,
     * or on the single icon in the trend slot; [trendDesc] on the trend beside a number).
     */
    data class Plan(
        val trend: String?,
        val number: String?,
        val desc: String,
        val trendDesc: String = desc,
    ) {
        companion object {
            val EMPTY = Plan(null, null, "")
        }
    }

    /**
     * The icons for [look]: fresh or late = the number and the trend arrow (5° steps, a double arrow from ±3); from
     * 5 min the number struck through at half strength beside the struck "no trend" ring; from 20 min only the "no
     * readings" icon in the trend slot ([since] = the last reading's time); an ended sensor the flag. A warming-up
     * sensor shows nothing: there is no number to draw yet (the notification and the card show the countdown).
     */
    fun plan(
        look: GlucoseLook,
        since: String?,
    ): Plan =
        when (look.state) {
            GlucoseLook.State.ENDED -> {
                Plan("monitor_ic_sensor_ended", null, ENDED)
            }

            GlucoseLook.State.NO_READINGS -> {
                Plan("monitor_ic_no_readings", null, "$NO_READINGS${since ?: "?"}")
            }

            GlucoseLook.State.NONE, GlucoseLook.State.WARMING_UP -> {
                Plan.EMPTY
            }

            GlucoseLook.State.VALUE -> {
                val text = checkNotNull(look.text)
                // card-ok: the look's text → its drawable name
                val v = text.lowercase(Locale.ROOT)
                val number =
                    when {
                        look.strike -> "sb_s$v"
                        v == "lo" || v == "hi" -> "sb_$v"
                        else -> "sb_n$v"
                    }
                val rate = look.rate
                val trend =
                    when {
                        look.trend == TrendMark.ARROW && rate != null -> trendDrawable(rate)
                        look.strike -> "sb_t_none_s"
                        else -> "sb_t_none"
                    }
                val mark = if (look.strike) "(old)" else TrendAngle.text(rate)
                Plan(trend, number, "$VALUE$text mg/dL $mark", "$TREND$mark")
            }
        }

    /** The arrow for [rate] (mg/dL/min): `sb_t00` straight up … `sb_t36` straight down, double arrows from ±3. */
    fun trendDrawable(rate: Double): String =
        when {
            TrendAngle.isDouble(rate) -> if (rate > 0) "sb_t_up2" else "sb_t_down2"
            else -> "sb_t%02d".format(Locale.ROOT, ((TrendAngle.deg(rate) + 90) / 5).roundToInt().coerceIn(0, 36))
        }

    /**
     * The slot daemon's commands for [plan] ([resId] resolves a drawable name), in the order to send them: System UI
     * puts the newest external icon first (leftmost), so the trend goes before the number. Descriptions are one line.
     */
    fun commands(
        plan: Plan,
        resId: (String) -> Int,
    ): List<String> {
        fun line(s: String) = s.replace('\n', ' ').replace('\r', ' ')
        val trendDesc = if (plan.number == null) plan.desc else plan.trendDesc
        val trend = plan.trend?.let { "set $SLOT_TREND ${resId(it)} 0 ${line(trendDesc)}" } ?: "remove $SLOT_TREND"
        val number = plan.number?.let { "set $SLOT_NUMBER ${resId(it)} 0 ${line(plan.desc)}" } ?: "remove $SLOT_NUMBER"
        return listOf(trend, number)
    }
}
