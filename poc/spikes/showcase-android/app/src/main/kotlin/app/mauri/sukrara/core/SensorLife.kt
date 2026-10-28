// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What we know about the current sensor (unix seconds). The owner learns it from the direct source
 * ([app.mauri.sukrara.direct.SensorPairing]) or Juggluco; followers and the Mac from the live event's `ss`/`se`/`sw`
 * (spikes/nostr/PROTOCOL-v2.md). [serial] is empty on followers (it isn't on the wire). [warmupEndEpoch] = when the
 * first reading is due (start + the sensor's warm-up; the family's nominal one is in its [SensorProfile]).
 */
data class SensorInfo(
    val serial: String, val startEpoch: Long, val endEpoch: Long,
    val warmupEndEpoch: Long = startEpoch + 60L * SensorLife.DEFAULT_WARMUP_MIN,
) {
    val lengthS get() = (endEpoch - startEpoch).coerceAtLeast(1)
}

/**
 * The sensor's remaining life: the value card's sensor badge ("13d" / "20h" / "45m") and countdown ring, the
 * live countdown notification and the reminders. Persisted in plain prefs (survives restarts); [current] is null
 * while nothing is known.
 */
object SensorLife {
    /**
     * Used when the sensor doesn't say (Juggluco source, followers without `sw`, pairings saved before the fields):
     * the Libre 3's profile ([SensorProfiles.LIBRE_3]): 15 days (LibreView's `wearDuration` 21600 min) and a 60 min
     * warm-up. Per-family values live in the profile, not here.
     */
    val DEFAULT_WEAR_MIN: Int = SensorProfiles.LIBRE_3.wearMin
    val DEFAULT_WARMUP_MIN: Int = SensorProfiles.LIBRE_3.warmupMin
    /** The warm-up state lasts until the first reading, at most this long past the warm-up's end. */
    const val WARMUP_GRACE_S = 15 * 60L
    /** The countdown window (ring on the card, live notification): last N hours, [COUNTDOWN_RANGE], default 8 (SettingsSpec). */
    val DEFAULT_COUNTDOWN_HOURS: Int = app.mauri.sukrara.settings.SettingsSpec.COUNTDOWN_HOURS.default.toInt()
    val COUNTDOWN_RANGE: IntRange = app.mauri.sukrara.settings.SettingsSpec.COUNTDOWN_HOURS.let { it.min.toInt()..it.max.toInt() }
    /** [h] as the countdown spec accepts it. */
    fun validCountdown(h: Int): Int = app.mauri.sukrara.settings.SettingsSpec.COUNTDOWN_HOURS.coerce(h.toDouble()).toInt()

    /**
     * The "sensor ended" glyph: Material Symbols Outlined `sports_score` (Apache 2.0), viewBox 0 -960 960 960. Also
     * `res/drawable/ic_stat_sensor_ended.xml` (notification icon, root status-bar slot, card, settings); the SystemUI hook
     * and the canvas card draw this path; the Mac uses SF Symbol `flag.checkered`.
     */
    const val FLAG_PATH = "M360-720h80v-80h-80v80Zm160 0v-80h80v80h-80ZM360-400v-80h80v80h-80Zm320-160v-80h80v80h-80Zm0 160v-80h80v80h-80Zm-160 0v-80h80v80h-80Zm160-320v-80h80v80h-80Zm-240 80v-80h80v80h-80ZM200-160v-640h80v80h80v80h-80v80h80v80h-80v320h-80Zm400-320v-80h80v80h-80Zm-160 0v-80h80v80h-80Zm-80-80v-80h80v80h-80Zm160 0v-80h80v80h-80Zm80-80v-80h80v80h-80Z"

    private const val PREFS = "sensor_life"
    private const val KEY_COUNTDOWN = "countdown_hours"

    private val _current = MutableStateFlow<SensorInfo?>(null)
    val current: StateFlow<SensorInfo?> = _current
    private val _ownCountdown = MutableStateFlow(DEFAULT_COUNTDOWN_HOURS)
    /** This device's stored countdown hours (a follower's "custom"); [setCountdownHours] edits it. */
    val ownCountdown: StateFlow<Int> = _ownCountdown
    private val _countdown = MutableStateFlow(DEFAULT_COUNTDOWN_HOURS)
    /**
     * The effective [countdownHours] as a flow, so surfaces redraw when the setting changes: the wearer's role = its
     * own; a follower's from its Settings from ([app.mauri.sukrara.settings.WearerSettings.effectiveCountdown]).
     */
    val countdown: StateFlow<Int> = _countdown

    /** [countdown] from [ownCountdown] and the follower's Settings from (called on a change of either). */
    fun recompute() { _countdown.value = app.mauri.sukrara.settings.WearerSettings.effectiveCountdown(_ownCountdown.value) }

    @Volatile private var loaded = false

    /** Loads the stored state (App.onCreate; also done lazily by the other calls). */
    fun init(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val end = p.getLong("end", 0)
        val start = p.getLong("start", 0)
        _current.value = if (end > 0) SensorInfo(p.getString("serial", "") ?: "", start, end,
            p.getLong("warmup", start + 60L * DEFAULT_WARMUP_MIN)) else null
        _ownCountdown.value = validCountdown(p.getInt(KEY_COUNTDOWN, DEFAULT_COUNTDOWN_HOURS))
        recompute()
        loaded = true
    }

    /** New sensor info (null = forget). No-op when unchanged. */
    fun update(ctx: Context, info: SensorInfo?) {
        init(ctx)
        if (_current.value == info) return
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (info == null) e.remove("serial").remove("start").remove("end").remove("warmup")
        else e.putString("serial", info.serial).putLong("start", info.startEpoch).putLong("end", info.endEpoch)
            .putLong("warmup", info.warmupEndEpoch)
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
        val h = validCountdown(hours)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_COUNTDOWN, h).apply()
        _ownCountdown.value = h
        recompute()
    }

    /** From the sensor's start, wear length and warm-up in minutes (0 or less = [DEFAULT_WEAR_MIN] / [DEFAULT_WARMUP_MIN]). */
    fun fromStart(serial: String, startEpoch: Long, wearMin: Int, warmupMin: Int = 0): SensorInfo =
        SensorInfo(serial, startEpoch, startEpoch + 60L * (if (wearMin > 0) wearMin else DEFAULT_WEAR_MIN),
            startEpoch + 60L * (if (warmupMin > 0) warmupMin else DEFAULT_WARMUP_MIN))

    // ------------------------------------------------------------------------------------- pure (shared fixtures)

    /**
     * Warming up: the sensor has started, no reading has come since its start ([latestT] = the newest reading's time,
     * null = none) and the warm-up isn't more than [WARMUP_GRACE_S] past its end. The card then shows the time left
     * instead of a value; the first reading ends it.
     */
    fun warming(info: SensorInfo, latestT: Long?, now: Long): Boolean =
        now >= info.startEpoch && now < info.warmupEndEpoch + WARMUP_GRACE_S && (latestT == null || latestT < info.startEpoch)

    /**
     * The warm-up's time left as the card's figure: whole minutes rounded up ("42m"), from 60 of them hours ("1h"). From the warm-up's
     * end until the first reading "Almost ready" ([waitingFirstReading]): a countdown never shows "0m" or less.
     */
    fun warmupLabel(info: SensorInfo, now: Long, w: Words = Words.current): String {
        // one rounding for both units: whole minutes rounded up, and from 60 of them hours (59:15 left is "1h" on every
        // surface, never "60m" on one and "1h" on another a few seconds apart)
        val min = (info.warmupEndEpoch - now + 59) / 60
        return when {
            waitingFirstReading(info, now) -> w.almostReady
            else -> Durations.whole(min, w)
        }
    }

    /** While warming up: the warm-up's time is over and the first reading hasn't come yet. */
    fun waitingFirstReading(info: SensorInfo, now: Long) = now >= info.warmupEndEpoch

    /** The warm-up ring over the main ring: fills 0 → 1 from the start to the warm-up's end. */
    fun warmupFraction(info: SensorInfo, now: Long): Double {
        val len = (info.warmupEndEpoch - info.startEpoch).coerceAtLeast(1)
        return ((now - info.startEpoch).toDouble() / len).coerceIn(0.0, 1.0)
    }

    /** Whether the sensor has reached its end. */
    fun ended(endEpoch: Long, now: Long) = now >= endEpoch

    /**
     * The badge text: ≥ 24 h left → whole days ("13d", floor), ≥ 1 h → hours ("20h", floor), else minutes ("45m",
     * floor); ended → "0m" (drawn in the alert colour, see [ended]).
     */
    fun label(endEpoch: Long, now: Long, w: Words = Words.current): String {
        val left = (endEpoch - now).coerceAtLeast(0)
        return Durations.whole(left / 60, w)
    }

    /** The plural quantity of [label] ("queda 1d", "quedan 13d"; [Durations.wholeQuantity]). */
    fun labelQuantity(endEpoch: Long, now: Long): Int = Durations.wholeQuantity((endEpoch - now).coerceAtLeast(0) / 60)

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
