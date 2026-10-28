// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import android.content.Intent
import android.os.Bundle
import app.mauri.sukrara.core.data.settings.HookPosition
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.GlucosePoint

/**
 * What the app tells the System UI module (one broadcast, [SystemUi.ACTION_STATE]): the root status-bar settings,
 * the person's ranges and the newest reading with the sensor state. The module recomputes the card's decision from it
 * every 30 s ([HookText.face]), so the value ages there without the app. [mgdl] 0 = no reading.
 */
data class HookState(
    val enabled: Boolean = false,
    val position: String = HookPosition.DEFAULT,
    val color: Boolean = true,
    val hideIcon: Boolean = true,
    val bands: Bands = Bands(),
    val mgdl: Int = 0,
    val atS: Long = 0,
    val rate: Double? = null,
    val sensorEnded: Boolean = false,
    val readyAtS: Long = 0,
) {
    /** The newest reading for the card's decision, or null. */
    fun latest(): GlucosePoint? = if (mgdl > 0) GlucosePoint(atS, mgdl, rate) else null

    /** Shown as system icons (the slot daemon's), which the module orders and colours. */
    val systemIcons: Boolean get() = enabled && HookPosition.isSystemIcons(position)

    fun toBundle(): Bundle =
        Bundle().apply {
            putBoolean(ENABLED, enabled)
            putString(POSITION, position)
            putBoolean(COLOR, color)
            putBoolean(HIDE_ICON, hideIcon)
            putIntArray(BANDS, intArrayOf(bands.veryLow, bands.low, bands.tight, bands.high, bands.veryHigh, bands.extremelyHigh))
            putInt(MGDL, mgdl)
            putLong(AT, atS)
            rate?.let { putDouble(RATE, it) }
            putBoolean(ENDED, sensorEnded)
            putLong(READY_AT, readyAtS)
        }

    companion object {
        private const val ENABLED = "enabled"
        private const val POSITION = "position"
        private const val COLOR = "color"
        private const val HIDE_ICON = "hideIcon"
        private const val BANDS = "bands"
        private const val MGDL = "mgdl"
        private const val AT = "at"
        private const val RATE = "rate"
        private const val ENDED = "sensorEnded"
        private const val READY_AT = "readyAt"

        /**
         * Reads a state the module received. Every value is untrusted and clamped: an unknown placement is the
         * default, a value outside 20–600 mg/dL or a rate outside ±20 is dropped, the ranges are made sane.
         */
        fun from(i: Intent): HookState {
            val b = i.extras ?: return HookState()
            val bands =
                b.getIntArray(BANDS)?.takeIf { it.size == 6 }?.let { Bands(it[0], it[1], it[2], it[3], it[4], it[5]).sane() }
                    ?: Bands()
            val mgdl = b.getInt(MGDL, 0).takeIf { it in 20..600 } ?: 0
            return HookState(
                enabled = b.getBoolean(ENABLED, false),
                position = b.getString(POSITION)?.takeIf { it in HookPosition.ALL } ?: HookPosition.DEFAULT,
                color = b.getBoolean(COLOR, true),
                hideIcon = b.getBoolean(HIDE_ICON, true),
                bands = bands,
                mgdl = mgdl,
                atS = if (mgdl > 0) b.getLong(AT, 0).coerceAtLeast(0) else 0,
                rate = if (b.containsKey(RATE)) b.getDouble(RATE).takeIf { it.isFinite() && it in -20.0..20.0 } else null,
                sensorEnded = b.getBoolean(ENDED, false),
                readyAtS = b.getLong(READY_AT, 0).coerceAtLeast(0),
            )
        }
    }
}

/** The names both sides of the System UI link use. */
object SystemUi {
    const val PACKAGE = "com.android.systemui"

    /** App → System UI: the [HookState] (sent only to System UI, with [permission]). */
    fun actionState(appPackage: String) = "$appPackage.SYSTEMUI_STATE"

    /** System UI → app: the module (re)started; extras [EXTRA_CODE], [EXTRA_ICON_ORDER]. */
    fun actionHello(appPackage: String) = "$appPackage.SYSTEMUI_HELLO"

    /** The signature permission the app's state broadcast needs (only this app can send it). */
    fun permission(appPackage: String) = "$appPackage.permission.SYSTEMUI_STATE"

    /** In a state broadcast: answer with a hello (the app process just started and wants the module's code). */
    const val EXTRA_ASK = "ask"
    const val EXTRA_CODE = "code"
    const val EXTRA_ICON_ORDER = "iconOrder"

    /**
     * Turns the module off on the next System UI start without LSPosed: `adb shell setprop debug.sukrara.systemui_off 1`
     * (or `settings put global sukrara_systemui_off 1`). Its own names, so it never turns off another module.
     */
    const val KILL_PROP = "debug.sukrara.systemui_off"
    const val KILL_SETTING = "sukrara_systemui_off"
}
