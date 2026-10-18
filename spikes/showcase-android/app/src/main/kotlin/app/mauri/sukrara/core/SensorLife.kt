// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What we know about the current sensor (unix seconds). The owner learns it from the direct source
 * ([app.mauri.sukrara.direct.SensorPairing]); followers and the Mac from the live event's `ss`/`se`
 * (spikes/nostr/PROTOCOL-v2.md). [serial] is empty on followers (it isn't on the wire).
 */
data class SensorInfo(val serial: String, val startEpoch: Long, val endEpoch: Long) {
    val lengthS get() = (endEpoch - startEpoch).coerceAtLeast(1)
}

/**
 * The sensor's remaining life: the value card's sensor badge ("13d" / "20h" / "45m") and countdown ring, the
 * live countdown notification and the reminders. Persisted in plain prefs (survives restarts); [current] is null
 * while nothing is known.
 */
object SensorLife {
    /** Libre 3 Plus: 15 days (LibreView's `wearDuration` 21600 min), used when the sensor doesn't say. */
    const val DEFAULT_WEAR_MIN = 21_600
    /** The countdown window (ring on the card, live notification): last N hours, [COUNTDOWN_RANGE], default 8. */
    const val DEFAULT_COUNTDOWN_HOURS = 8
    val COUNTDOWN_RANGE = 1..24

    private const val PREFS = "sensor_life"
    private const val KEY_COUNTDOWN = "countdown_hours"

    private val _current = MutableStateFlow<SensorInfo?>(null)
    val current: StateFlow<SensorInfo?> = _current
    private val _countdown = MutableStateFlow(DEFAULT_COUNTDOWN_HOURS)
    /** [countdownHours] as a flow, so surfaces redraw when the setting changes. */
    val countdown: StateFlow<Int> = _countdown

    @Volatile private var loaded = false

    /** Loads the stored state (App.onCreate; also done lazily by the other calls). */
    fun init(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val end = p.getLong("end", 0)
        _current.value = if (end > 0) SensorInfo(p.getString("serial", "") ?: "", p.getLong("start", 0), end) else null
        _countdown.value = p.getInt(KEY_COUNTDOWN, DEFAULT_COUNTDOWN_HOURS).coerceIn(COUNTDOWN_RANGE)
        loaded = true
    }

    /** New sensor info (null = forget). No-op when unchanged. */
    fun update(ctx: Context, info: SensorInfo?) {
        init(ctx)
        if (_current.value == info) return
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (info == null) e.remove("serial").remove("start").remove("end")
        else e.putString("serial", info.serial).putLong("start", info.startEpoch).putLong("end", info.endEpoch)
        e.apply()
        _current.value = info
    }

    /**
     * The sensor said it has ended (patch state / life count, `direct/PatchState`): its end moves to [now] if that is
     * earlier, so every surface shows the ended state ("0m" in the alert colour, "Sensor ended" once readings stop).
     */
    fun markEnded(ctx: Context, serial: String, now: Long) {
        init(ctx)
        val c = _current.value?.takeIf { it.serial == serial } ?: return
        if (c.endEpoch > now) update(ctx, c.copy(endEpoch = now))
    }

    /** Whether the known sensor has ended at [now] (false when nothing is known). */
    fun hasEnded(now: Long): Boolean = _current.value?.let { ended(it.endEpoch, now) } ?: false

    fun countdownHours(ctx: Context): Int { init(ctx); return _countdown.value }

    fun setCountdownHours(ctx: Context, hours: Int) {
        init(ctx)
        val h = hours.coerceIn(COUNTDOWN_RANGE)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_COUNTDOWN, h).apply()
        _countdown.value = h
    }

    /** From the sensor's start and wear length in minutes (0 or less = [DEFAULT_WEAR_MIN]). */
    fun fromStart(serial: String, startEpoch: Long, wearMin: Int): SensorInfo =
        SensorInfo(serial, startEpoch, startEpoch + 60L * (if (wearMin > 0) wearMin else DEFAULT_WEAR_MIN))

    // ------------------------------------------------------------------------------------- pure (shared fixtures)

    /** Whether the sensor has reached its end. */
    fun ended(endEpoch: Long, now: Long) = now >= endEpoch

    /**
     * The badge text: ≥ 24 h left → whole days ("13d", floor), ≥ 1 h → hours ("20h", floor), else minutes ("45m",
     * floor); ended → "0m" (drawn in the alert colour, see [ended]).
     */
    fun label(endEpoch: Long, now: Long, w: Words = Words.current): String {
        val left = (endEpoch - now).coerceAtLeast(0)
        return when {
            left >= 86_400 -> w.durDays.format(left / 86_400)
            left >= 3_600 -> w.durHours.format(left / 3_600)
            else -> w.durMinutes.format(left / 60)
        }
    }

    /** The badge disc's arc: the share of the sensor's life still left, 1 → 0. */
    fun lifeFraction(info: SensorInfo, now: Long): Double =
        ((info.endEpoch - now).toDouble() / info.lengthS).coerceIn(0.0, 1.0)

    /**
     * The countdown ring over the card's main ring: null outside the last [hours] (no ring), else the share of the
     * window still left, 1 (window starts) → 0 (sensor ended). Null after the end too: the badge says "0m".
     */
    fun countdownFraction(endEpoch: Long, now: Long, hours: Int): Double? {
        val left = endEpoch - now
        val window = hours * 3_600L
        return if (left <= 0 || left > window) null else left.toDouble() / window
    }
}
